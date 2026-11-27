package com.green.keyboard_project.service.email;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import static com.green.keyboard_project.service.email.EmailVerificationException.Code.*;

/** 단일 프로세스에서 발급 번호, 가입 증명과 요청 한도를 관리합니다. */
@Component
public class EmailVerificationStore {
    static final long CODE_SECONDS = 300;
    static final long RESEND_SECONDS = 60;
    static final long TOKEN_SECONDS = 600;
    private static final int CODE_FAILURE_LIMIT = 5;
    private static final int EMAIL_SEND_LIMIT = 5;
    private static final int EMAIL_VERIFY_LIMIT = 15;
    private static final int IP_SEND_LIMIT = 20;
    private static final int IP_VERIFY_LIMIT = 100;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final Duration DAY = Duration.ofDays(1);
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final ConcurrentHashMap<String, MailState> emails = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Limits> ipLimits = new ConcurrentHashMap<>();
    private final Counter totalSends = new Counter(DAY, 100);
    private final Counter totalVerifications = new Counter(WINDOW, 600);
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec hmacKey;
    private final Clock clock;
    private final int capacity;

    public EmailVerificationStore() {
        this(Clock.systemUTC(), 10_000);
    }

    EmailVerificationStore(Clock clock, int capacity) {
        this.clock = clock;
        this.capacity = capacity;
        // 번호는 경우의 수가 적어 프로세스 내부에서만 사용하는 비밀키로 검증값을 만듭니다.
        hmacKey = new SecretKeySpec(randomBytes(32), "HmacSHA256");
    }

    record SendReservation(String email, String challengeId, String code) {
        @Override public String toString() { return "SendReservation[redacted]"; }
    }

    record SignupReservation(String emailKey, Proof proof) {}

    // 여러 카운터와 이메일 상태의 검사·갱신을 함께 잠급니다. DB 조회와 메일 호출은 이 잠금 밖에서 합니다.
    synchronized SendReservation beginSend(String email, String ip) {
        Instant now = clock.instant();
        String key = emailKey(email);
        MailState state = emails.get(key);
        Limits ipRate = ipLimits.get(ip);
        totalSends.check(now);
        if (ipRate != null) ipRate.send.check(now);
        if (state != null) {
            state.limits.send.check(now);
            Instant nextSend = state.challenge != null && !state.challenge.sent
                    && state.challenge.expiresAt.isAfter(state.nextSend)
                    ? state.challenge.expiresAt : state.nextSend;
            if (now.isBefore(nextSend)) throw EmailVerificationException.rateLimited(secondsUntil(now, nextSend));
        }

        ensureCapacity(now, state == null, ipRate == null);
        state = emails.computeIfAbsent(key, ignored -> new MailState());
        ipRate = ipLimits.computeIfAbsent(ip, ignored -> new Limits(IP_SEND_LIMIT, IP_VERIFY_LIMIT));
        totalSends.increment(now);
        ipRate.send.increment(now);
        state.limits.send.increment(now);
        state.nextSend = now.plusSeconds(RESEND_SECONDS);

        String id = URL_ENCODER.encodeToString(randomBytes(16));
        String code;
        // 재발급 ID가 바뀌고 바로 전 번호의 숫자도 우연히 다시 뽑히지 않도록 합니다.
        do {
            code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        } while (state.challenge != null
                && MessageDigest.isEqual(state.challenge.hash, hash("code", state.challenge.id, code)));
        state.challenge = new Challenge(email, id, hash("code", id, code), now.plusSeconds(CODE_SECONDS));
        return new SendReservation(email, id, code);
    }

    synchronized boolean isSendPending(SendReservation reservation) {
        Challenge challenge = challengeFor(reservation);
        return challenge != null && !challenge.sent && clock.instant().isBefore(challenge.expiresAt);
    }

    synchronized boolean finishSend(SendReservation reservation, boolean deliveredToProvider) {
        Challenge challenge = challengeFor(reservation);
        if (challenge == null) return false;
        MailState state = emails.get(emailKey(reservation.email));
        if (!deliveredToProvider || !clock.instant().isBefore(challenge.expiresAt)) {
            state.challenge = null;
            return false;
        }
        challenge.sent = true;
        return true;
    }

    private Challenge challengeFor(SendReservation reservation) {
        MailState state = emails.get(emailKey(reservation.email));
        Challenge challenge = state == null ? null : state.challenge;
        return challenge != null && challenge.id.equals(reservation.challengeId) ? challenge : null;
    }

    synchronized String verify(String email, String ip, String challengeId, String code) {
        Instant now = clock.instant();
        MailState state = emails.get(emailKey(email));
        Limits ipRate = ipLimits.get(ip);
        totalVerifications.check(now);
        if (ipRate != null) ipRate.verify.check(now);
        if (state != null) state.limits.verify.check(now);
        ensureCapacity(now, false, ipRate == null);
        ipRate = ipLimits.computeIfAbsent(ip, ignored -> new Limits(IP_SEND_LIMIT, IP_VERIFY_LIMIT));
        totalVerifications.increment(now);
        ipRate.verify.increment(now);
        if (state != null) state.limits.verify.increment(now);

        Challenge challenge = state == null ? null : state.challenge;
        if (challenge == null || !challenge.sent || !now.isBefore(challenge.expiresAt)
                || !challenge.email.equals(email) || !challenge.id.equals(challengeId)) {
            throw new EmailVerificationException(INVALID_OR_EXPIRED_CODE);
        }
        if (challenge.failures >= CODE_FAILURE_LIMIT) {
            throw EmailVerificationException.rateLimited(secondsUntil(now, challenge.expiresAt));
        }
        if (!MessageDigest.isEqual(challenge.hash, hash("code", challenge.id, code))) {
            challenge.failures++;
            if (challenge.failures == CODE_FAILURE_LIMIT) {
                throw EmailVerificationException.rateLimited(secondsUntil(now, challenge.expiresAt));
            }
            throw new EmailVerificationException(INVALID_OR_EXPIRED_CODE);
        }
        if (state.proof != null && state.proof.reserved) {
            throw new EmailVerificationException(VERIFICATION_IN_USE);
        }

        // 번호 폐기와 증명 발급을 같은 잠금 안에서 처리해 한 번호로 두 증명을 받을 수 없게 합니다.
        String token = URL_ENCODER.encodeToString(randomBytes(32));
        state.proof = new Proof(email, hash("signup", email, token), now.plusSeconds(TOKEN_SECONDS));
        state.challenge = null;
        return token;
    }

    synchronized SignupReservation reserveSignup(String email, String token) {
        if (email == null || token == null || token.length() != 43) {
            throw new EmailVerificationException(VERIFICATION_REQUIRED);
        }
        String key = emailKey(email);
        MailState state = emails.get(key);
        Proof proof = state == null ? null : state.proof;
        if (proof == null || !proof.email.equals(email) || !clock.instant().isBefore(proof.expiresAt)
                || !MessageDigest.isEqual(proof.hash, hash("signup", email, token))) {
            throw new EmailVerificationException(VERIFICATION_REQUIRED);
        }
        if (proof.reserved) throw new EmailVerificationException(VERIFICATION_IN_USE);
        proof.reserved = true;
        return new SignupReservation(key, proof);
    }

    synchronized void finishSignup(SignupReservation reservation, boolean rolledBack) {
        MailState state = emails.get(reservation.emailKey);
        if (state == null || state.proof != reservation.proof) return;
        // 커밋 또는 결과 불명은 증명을 폐기합니다. 유효기간 안의 확정 롤백만 재시도할 수 있습니다.
        if (rolledBack && clock.instant().isBefore(state.proof.expiresAt)) {
            state.proof.reserved = false;
        } else {
            state.proof = null;
        }
    }

    @Scheduled(fixedDelay = 60_000)
    synchronized void removeExpired() {
        removeExpired(clock.instant());
    }

    private void ensureCapacity(Instant now, boolean newEmail, boolean newIp) {
        if ((newEmail && emails.size() >= capacity) || (newIp && ipLimits.size() >= capacity)) {
            removeExpired(now);
            if ((newEmail && emails.size() >= capacity) || (newIp && ipLimits.size() >= capacity)) {
                throw EmailVerificationException.rateLimited(RESEND_SECONDS);
            }
        }
    }

    private void removeExpired(Instant now) {
        emails.values().removeIf(state -> {
            if (state.challenge != null && !now.isBefore(state.challenge.expiresAt)) state.challenge = null;
            if (state.proof != null && !state.proof.reserved && !now.isBefore(state.proof.expiresAt)) {
                state.proof = null;
            }
            return state.challenge == null && state.proof == null && !now.isBefore(state.nextSend)
                    && state.limits.expired(now);
        });
        ipLimits.values().removeIf(limits -> limits.expired(now));
    }

    private static String emailKey(String email) {
        // 대소문자 변형으로 한도를 피할 수 없게 묶되, 번호와 증명은 발송에 사용한 원래 주소에 묶습니다.
        return email.toLowerCase(Locale.ROOT);
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    private byte[] hash(String purpose, String owner, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            return mac.doFinal((purpose + "\0" + owner + "\0" + secret).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("이메일 인증 검증값을 만들 수 없습니다.", error);
        }
    }

    private static long secondsUntil(Instant now, Instant end) {
        return Duration.between(now, end).plusNanos(999_999_999).toSeconds();
    }

    private static final class MailState {
        final Limits limits = new Limits(EMAIL_SEND_LIMIT, EMAIL_VERIFY_LIMIT);
        Instant nextSend = Instant.EPOCH;
        Challenge challenge;
        Proof proof;
    }

    private static final class Challenge {
        final String email;
        final String id;
        final byte[] hash;
        final Instant expiresAt;
        boolean sent;
        int failures;

        Challenge(String email, String id, byte[] hash, Instant expiresAt) {
            this.email = email;
            this.id = id;
            this.hash = hash;
            this.expiresAt = expiresAt;
        }
    }

    private static final class Proof {
        final String email;
        final byte[] hash;
        final Instant expiresAt;
        boolean reserved;

        Proof(String email, byte[] hash, Instant expiresAt) {
            this.email = email;
            this.hash = hash;
            this.expiresAt = expiresAt;
        }
    }

    private record Limits(Counter send, Counter verify) {
        Limits(int sends, int verifications) {
            this(new Counter(WINDOW, sends), new Counter(WINDOW, verifications));
        }

        boolean expired(Instant now) { return send.expired(now) && verify.expired(now); }
    }

    // 제한 창을 재발송이나 실패 처리와 분리해 번호를 바꿔도 누적 횟수가 사라지지 않게 합니다.
    private static final class Counter {
        private final Duration window;
        private final int limit;
        private Instant until = Instant.EPOCH;
        private int count;

        Counter(Duration window, int limit) {
            this.window = window;
            this.limit = limit;
        }

        boolean expired(Instant now) { return !now.isBefore(until); }

        void check(Instant now) {
            if (!expired(now) && count >= limit) {
                throw EmailVerificationException.rateLimited(secondsUntil(now, until));
            }
        }

        void increment(Instant now) {
            if (expired(now)) {
                until = now.plus(window);
                count = 0;
            }
            count++;
        }
    }
}
