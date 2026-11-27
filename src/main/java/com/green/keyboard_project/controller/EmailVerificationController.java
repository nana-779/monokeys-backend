package com.green.keyboard_project.controller;

import com.green.keyboard_project.dto.ApiResponse;
import com.green.keyboard_project.dto.SignupEmailRequest;
import com.green.keyboard_project.service.email.EmailVerificationException;
import com.green.keyboard_project.service.email.EmailVerificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import static com.green.keyboard_project.dto.SignupRequest.EMAIL_FORMAT_MESSAGE;
import static com.green.keyboard_project.dto.SignupRequest.EMAIL_PATTERN;
import static com.green.keyboard_project.service.email.EmailVerificationException.Code.INVALID_EMAIL_VERIFICATION;

/** 가입 전 호출하는 이메일 발송·검증 API입니다. 세션이나 로그인 쿠키가 필요하지 않습니다. */
@RestController
@RequestMapping(path = "/api/auth/email", consumes = MediaType.APPLICATION_JSON_VALUE)
public class EmailVerificationController {
    private final EmailVerificationService verification;

    public EmailVerificationController(EmailVerificationService verification) {
        this.verification = verification;
    }

    public record VerifyRequest(
            @NotBlank @Email(regexp = EMAIL_PATTERN, message = EMAIL_FORMAT_MESSAGE)
            @Size(max = 50, message = EMAIL_FORMAT_MESSAGE) String email,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{22}") String challengeId,
            JsonNode code
    ) {
        @Override public String toString() { return "VerifyRequest[redacted]"; }
    }

    @PostMapping("/send")
    public ResponseEntity<ApiResponse<EmailVerificationService.SendResult>> send(
            @Valid @RequestBody SignupEmailRequest body, HttpServletRequest request) {
        // 직접 받은 X-Forwarded-For 값은 사용하지 않습니다. 연결 IP는 서블릿 컨테이너의 값을 사용합니다.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(verification.send(body.email(), request.getRemoteAddr())));
    }

    @PostMapping("/verify")
    public ResponseEntity<ApiResponse<EmailVerificationService.VerifyResult>> verify(
            @Valid @RequestBody VerifyRequest body, HttpServletRequest request) {
        // 숫자 JSON을 문자열로 바꾸지 않고 앞자리 0을 보존하는 6자리 문자열만 받습니다.
        String code = body.code() != null && body.code().isString() ? body.code().stringValue() : null;
        if (code == null || !code.matches("[0-9]{6}")) {
            throw new EmailVerificationException(INVALID_EMAIL_VERIFICATION);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(verification.verify(body.email(), request.getRemoteAddr(), body.challengeId(), code)));
    }
}
