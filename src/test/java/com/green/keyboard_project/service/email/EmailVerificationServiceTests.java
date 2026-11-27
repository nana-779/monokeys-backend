package com.green.keyboard_project.service.email;

import com.green.keyboard_project.repository.UserRepository;
import org.junit.jupiter.api.Test;

import static com.green.keyboard_project.service.email.EmailVerificationException.Code.*;
import static com.green.keyboard_project.service.email.EmailVerificationStoreTests.assertFailure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 실제 발송과 DB 연결 없이 메일 설정, 발송 실패와 중복 주소 처리만 검증합니다. */
class EmailVerificationServiceTests {
    private static final String EMAIL = "owner@example.com";
    private static final String IP = "192.0.2.1";
    private final EmailVerificationStoreTests.MutableClock clock = new EmailVerificationStoreTests.MutableClock();
    private final UserRepository users = mock(UserRepository.class);
    private final ResendEmailService mail = mock(ResendEmailService.class);
    private final EmailVerificationService service =
            new EmailVerificationService(new EmailVerificationStore(clock, 100), mail, users);

    @Test
    void providerFailureDoesNotRetryAndKeepsTheResendLimit() {
        when(mail.isConfigured()).thenReturn(true);
        var failure = mock(ResendEmailService.EmailDeliveryException.class);
        when(failure.getFailure()).thenReturn(ResendEmailService.Failure.SEND_FAILED);
        when(mail.sendVerificationCode(eq(EMAIL), anyString())).thenThrow(failure).thenReturn("accepted");

        assertFailure(EMAIL_SEND_FAILED, () -> service.send(EMAIL, IP));
        assertFailure(EMAIL_VERIFICATION_RATE_LIMITED, () -> service.send(EMAIL, IP));
        verify(mail, times(1)).sendVerificationCode(eq(EMAIL), anyString());
        clock.advance(60);
        assertThat(service.send(EMAIL, IP).challengeId()).hasSize(22);
    }

    @Test
    void existingEmailReturnsSameResponseShapeWithoutSendingMail() {
        when(mail.isConfigured()).thenReturn(true);
        when(users.existsByEmail(EMAIL)).thenReturn(true);

        var result = service.send(EMAIL, IP);
        assertThat(result.challengeId()).hasSize(22);
        assertThat(result.expiresInSeconds()).isEqualTo(300);
        verify(mail, never()).sendVerificationCode(anyString(), anyString());
        assertFailure(INVALID_OR_EXPIRED_CODE,
                () -> service.verify(EMAIL, IP, result.challengeId(), "000000"));
    }

    @Test
    void noConfigurationDoesNotCallDatabaseOrMailProvider() {
        assertFailure(EMAIL_VERIFICATION_UNAVAILABLE, () -> service.send(EMAIL, IP));
        verifyNoInteractions(users);
        verify(mail, never()).sendVerificationCode(anyString(), anyString());
    }
}
