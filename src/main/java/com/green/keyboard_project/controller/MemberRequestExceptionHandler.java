package com.green.keyboard_project.controller;

import com.green.keyboard_project.dto.ApiResponse;
import com.green.keyboard_project.service.email.EmailVerificationException;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/** 회원 요청의 입력 오류와 이메일 인증 실패를 민감한 원본 정보 없이 반환합니다. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {UserController.class, EmailVerificationController.class})
public class MemberRequestExceptionHandler {
    public record VerificationError(String code) {}

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidJson(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(ApiResponse.fail("요청 형식을 확인해 주세요."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> invalidInput(MethodArgumentNotValidException exception) {
        // 전체 검증 예외에는 거부된 입력값이 포함될 수 있으므로 필드명과 미리 정해진 문구만 반환합니다.
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + "은(는)" + error.getDefaultMessage() + "\n")
                .collect(Collectors.joining());
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(ApiResponse.fail(message));
    }

    @ExceptionHandler(EmailVerificationException.class)
    public ResponseEntity<ApiResponse<VerificationError>> verificationFailure(EmailVerificationException error) {
        var response = ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore());
        if (error.retryAfterSeconds() != null) {
            response.header(HttpHeaders.RETRY_AFTER, error.retryAfterSeconds().toString());
        }
        return response.body(ApiResponse.<VerificationError>builder().success(false)
                .message(error.getMessage()).data(new VerificationError(error.code().name())).build());
    }
}
