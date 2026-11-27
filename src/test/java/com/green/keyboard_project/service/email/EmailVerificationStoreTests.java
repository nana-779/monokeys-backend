package com.green.keyboard_project.service.email;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.green.keyboard_project.service.email.EmailVerificationException.Code.*;
import static org.assertj.core.api.Assertions.*;

/** 실행 시간을 직접 움직여 만료와 제한을 실시간 대기 없이 검증합니다. */
class EmailVerificationStoreTests {
    private static final String EMAIL = "owner@example.com";
    private static final String IP = "192.0.2.1";
    private final MutableClock clock = new MutableClock();
    private final EmailVerificationStore store = new EmailVerificationStore(clock, 1_000);

    @Test
    void codeAndTokenAreBoundToTheEmailAndUsedOnce() {
        var challenge = activeCode(EMAIL);
        assertThat(challenge.code()).matches("[0-9]{6}");
        assertFailure(INVALID_OR_EXPIRED_CODE, () -> verify("OWNER@example.com", challenge));
        String token = verify(EMAIL, challenge);
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertFailure(INVALID_OR_EXPIRED_CODE, () -> verify(EMAIL, challenge));
        assertFailure(VERIFICATION_REQUIRED, () -> store.reserveSignup("other@example.com", token));
        assertFailure(VERIFICATION_REQUIRED, () -> store.reserveSignup(EMAIL, null));
        var reservation = store.reserveSignup(EMAIL, token);
        assertFailure(VERIFICATION_IN_USE, () -> store.reserveSignup(EMAIL, token));
        store.finishSignup(reservation, false);
        assertFailure(VERIFICATION_REQUIRED, () -> store.reserveSignup(EMAIL, token));
    }

    @Test
    void expiresCodesAndSignupProofAtTheirExactBoundaries() {
        var expired = activeCode(EMAIL);
        clock.advance(300);
        assertFailure(INVALID_OR_EXPIRED_CODE, () -> verify(EMAIL, expired));
        var current = activeCode(EMAIL);
        clock.advance(299);
        String token = verify(EMAIL, current);
        clock.advance(599);
        var reservation = store.reserveSignup(EMAIL, token);
        store.finishSignup(reservation, true);
        clock.advance(1);
        assertFailure(VERIFICATION_REQUIRED, () -> store.reserveSignup(EMAIL, token));
    }

    @Test
    void resendActivatesOnlyTheNewCodeAfterSixtySeconds() {
        var first = activeCode(EMAIL);
        clock.advance(59);
        assertFailure(EMAIL_VERIFICATION_RATE_LIMITED, () -> activeCode("OWNER@example.com"));
        clock.advance(1);
        var second = store.beginSend(EMAIL, IP);
        assertThat(second.code()).isNotEqualTo(first.code());
        assertFailure(INVALID_OR_EXPIRED_CODE, () -> verify(EMAIL, first));
        assertFailure(INVALID_OR_EXPIRED_CODE, () -> verify(EMAIL, second));
        assertThat(store.finishSend(first, true)).isFalse();
        store.finishSend(second, true);
        assertThat(verify(EMAIL, second)).isNotBlank();
    }

    @Test
    void fifthFailureLocksTheCodeAndResendDoesNotResetTheEmailQuota() {
        for (int issue = 0; issue < 2; issue++) {
            var challenge = activeCode(EMAIL);
            String wrong = "000000".equals(challenge.code()) ? "000001" : "000000";
            for (int attempt = 0; attempt < 4; attempt++) {
                assertFailure(INVALID_OR_EXPIRED_CODE, () -> store.verify(EMAIL, IP, challenge.challengeId(), wrong));
            }
            assertFailure(EMAIL_VERIFICATION_RATE_LIMITED,
                    () -> store.verify(EMAIL, IP, challenge.challengeId(), wrong));
            clock.advance(60);
        }
        var blocked = activeCode(EMAIL);
        for (int attempt = 0; attempt < 5; attempt++) {
            // 틀린 발급 ID도 누적 조회 한도에 포함되지만, 현재 번호의 실패 횟수에는 포함되지 않습니다.
            assertFailure(INVALID_OR_EXPIRED_CODE, () -> store.verify(EMAIL, IP, "unknown", blocked.code()));
        }
        assertFailure(EMAIL_VERIFICATION_RATE_LIMITED, () -> verify(EMAIL, blocked));
    }

    @Test
    void limitsEmailIpAndTotalSendsIndependently() {
        for (int i = 0; i < 5; i++) {
            activeCode(EMAIL);
            clock.advance(60);
        }
        assertFailure(EMAIL_VERIFICATION_RATE_LIMITED, () -> activeCode(EMAIL));
        for (int i = 0; i < 15; i++) activeCode("person" + i + "@example.com");
        assertFailure(EMAIL_VERIFICATION_RATE_LIMITED, () -> activeCode("new@example.com"));
        for (int i = 20; i < 100; i++) {
            store.beginSend("person" + i + "@example.com", "192.0.2." + i);
        }
        assertFailure(EMAIL_VERIFICATION_RATE_LIMITED,
                () -> store.beginSend("new@example.com", "192.0.2.120"));
    }

    @Test
    void capacityRejectsNewEntriesUntilExpiredEntriesAreCleanedUp() {
        var small = new EmailVerificationStore(clock, 1);
        var first = small.beginSend(EMAIL, IP);
        small.finishSend(first, false);
        assertFailure(EMAIL_VERIFICATION_RATE_LIMITED,
                () -> small.beginSend("other@example.com", "192.0.2.2"));
        clock.advance(900);
        small.removeExpired();
        assertThat(small.beginSend("other@example.com", "192.0.2.2").code()).hasSize(6);
    }

    @Test
    void concurrentRequestsCanReserveSendVerifyAndSignupOnlyOnce() throws Exception {
        // 같은 대상에 여러 스레드를 동시에 진입시켜 각 상태 전이의 성공 횟수를 확인합니다.
        var sent = runTogether(() -> store.beginSend(EMAIL, IP));
        assertThat(sent).hasSize(1);
        var challenge = sent.getFirst();
        store.finishSend(challenge, true);
        var verified = runTogether(() -> verify(EMAIL, challenge));
        assertThat(verified).hasSize(1);
        var reserved = runTogether(() -> store.reserveSignup(EMAIL, verified.getFirst()));
        assertThat(reserved).hasSize(1);
    }

    private EmailVerificationStore.SendReservation activeCode(String email) {
        var challenge = store.beginSend(email, IP);
        assertThat(store.finishSend(challenge, true)).isTrue();
        return challenge;
    }

    private String verify(String email, EmailVerificationStore.SendReservation challenge) {
        return store.verify(email, IP, challenge.challengeId(), challenge.code());
    }

    static void assertFailure(EmailVerificationException.Code code, Runnable action) {
        assertThatExceptionOfType(EmailVerificationException.class).isThrownBy(action::run)
                .satisfies(error -> assertThat(error.code()).isEqualTo(code));
    }

    private static <T> List<T> runTogether(Callable<T> action) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<T>>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return action.call();
                    } catch (EmailVerificationException expected) {
                        return null;
                    }
                }));
            }
            start.countDown();
            var successes = new ArrayList<T>();
            for (var future : futures) {
                T result = future.get(5, TimeUnit.SECONDS);
                if (result != null) successes.add(result);
            }
            return successes;
        }
    }

    static final class MutableClock extends Clock {
        private Instant time = Instant.parse("2026-01-01T00:00:00Z");
        void advance(long seconds) { time = time.plusSeconds(seconds); }
        @Override public Instant instant() { return time; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(time, zone); }
    }
}
