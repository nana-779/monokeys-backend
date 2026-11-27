package com.green.keyboard_project.service.email;

import org.springframework.http.HttpStatus;

/** 공개 API에서 안전하게 표시할 오류만 보관합니다. 인증번호와 토큰은 포함하지 않습니다. */
public final class EmailVerificationException extends RuntimeException {
    public enum Code {
        EMAIL_VERIFICATION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "이메일 인증을 현재 사용할 수 없습니다."),
        EMAIL_SEND_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "인증 메일 발송 결과를 확인하지 못했습니다."),
        INVALID_EMAIL_VERIFICATION(HttpStatus.BAD_REQUEST, "이메일 주소, 발급 번호 또는 인증번호를 확인해 주세요."),
        INVALID_OR_EXPIRED_CODE(HttpStatus.BAD_REQUEST, "인증번호가 맞지 않거나 만료됐습니다."),
        VERIFICATION_REQUIRED(HttpStatus.FORBIDDEN, "이메일 인증을 다시 완료해 주세요."),
        VERIFICATION_IN_USE(HttpStatus.CONFLICT, "이메일 인증을 사용한 가입 요청을 처리 중입니다."),
        EMAIL_VERIFICATION_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "잠시 후 다시 시도해 주세요.");

        private final HttpStatus status;
        private final String message;

        Code(HttpStatus status, String message) {
            this.status = status;
            this.message = message;
        }
    }

    private final Code code;
    private final Long retryAfterSeconds;

    public EmailVerificationException(Code code) {
        this(code, null);
    }

    private EmailVerificationException(Code code, Long retryAfterSeconds) {
        super(code.message);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    static EmailVerificationException rateLimited(long seconds) {
        return new EmailVerificationException(Code.EMAIL_VERIFICATION_RATE_LIMITED, seconds);
    }

    public Code code() { return code; }
    public HttpStatus status() { return code.status; }
    public Long retryAfterSeconds() { return retryAfterSeconds; }
}
