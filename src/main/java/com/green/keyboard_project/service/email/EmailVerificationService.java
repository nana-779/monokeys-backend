package com.green.keyboard_project.service.email;

import com.green.keyboard_project.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static com.green.keyboard_project.service.email.EmailVerificationException.Code.*;
import static com.green.keyboard_project.service.email.EmailVerificationStore.*;

/** 발송 서비스와 임시 저장소를 연결하고 가입 트랜잭션이 끝날 때 증명을 정리합니다. */
@Service
public class EmailVerificationService {
    private final EmailVerificationStore store;
    private final ResendEmailService mail;
    private final UserRepository users;

    public EmailVerificationService(EmailVerificationStore store, ResendEmailService mail, UserRepository users) {
        this.store = store;
        this.mail = mail;
        this.users = users;
    }

    public record SendResult(String challengeId, long expiresInSeconds, long resendAfterSeconds) {}

    public record VerifyResult(String verificationToken, long expiresInSeconds) {
        @Override public String toString() { return "VerifyResult[redacted]"; }
    }

    public SendResult send(String email, String ip) {
        requireMailConfiguration();
        var reservation = store.beginSend(email, ip);
        boolean accepted = false;
        try {
            // 이미 가입한 주소에는 메일을 보내지 않되 외부 응답은 같은 모양으로 반환합니다.
            if (!users.existsByEmail(email)) {
                // DB 조회가 오래 걸려 예약이 만료됐으면 오래된 번호를 보내지 않습니다.
                if (!store.isSendPending(reservation)) throw new EmailVerificationException(EMAIL_SEND_FAILED);
                mail.sendVerificationCode(email, reservation.code());
                accepted = true;
            }
        } catch (ResendEmailService.EmailDeliveryException error) {
            throw new EmailVerificationException(switch (error.getFailure()) {
                case NOT_CONFIGURED -> EMAIL_VERIFICATION_UNAVAILABLE;
                case INVALID_MESSAGE -> INVALID_EMAIL_VERIFICATION;
                case SEND_FAILED -> EMAIL_SEND_FAILED;
            });
        } finally {
            // 오류나 결과 불명 상태의 번호는 활성화하지 않습니다. 발송 제한은 저장소에 남습니다.
            if (!accepted) store.finishSend(reservation, false);
        }
        if (accepted && !store.finishSend(reservation, true)) {
            throw new EmailVerificationException(EMAIL_SEND_FAILED);
        }
        return new SendResult(reservation.challengeId(), CODE_SECONDS, RESEND_SECONDS);
    }

    public VerifyResult verify(String email, String ip, String challengeId, String code) {
        requireMailConfiguration();
        return new VerifyResult(store.verify(email, ip, challengeId, code), TOKEN_SECONDS);
    }

    public void reserveForSignup(String email, String token) {
        requireMailConfiguration();
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("가입 인증에는 회원 저장 트랜잭션이 필요합니다.");
        }
        var reservation = store.reserveSignup(email, token);
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    // saveAndFlush() 이후에도 DB가 롤백할 수 있어 실제 트랜잭션 결과를 기다립니다.
                    store.finishSignup(reservation, status == STATUS_ROLLED_BACK);
                }
            });
        } catch (RuntimeException error) {
            store.finishSignup(reservation, false);
            throw error;
        }
    }

    private void requireMailConfiguration() {
        if (!mail.isConfigured()) throw new EmailVerificationException(EMAIL_VERIFICATION_UNAVAILABLE);
    }
}
