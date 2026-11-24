package com.green.keyboard_project.controller;

import com.green.keyboard_project.dto.ApiResponse;
import com.green.keyboard_project.dto.payment.OrderCancellationRequest;
import com.green.keyboard_project.exceptions.PaymentConfirmationException;
import com.green.keyboard_project.service.payment.OrderCancellationService;
import com.green.keyboard_project.service.payment.OrderCancellationService.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/** 회원 주문 취소 요청의 인증, 입력 형식과 오류 응답을 담당합니다. */
@RestController
public class OrderCancellationController {
    private final OrderCancellationService cancellations;
    private final String allowedOrigin;

    public OrderCancellationController(OrderCancellationService cancellations,
            @Value("${cors.allowed-origin:http://localhost:3000}") String allowedOrigin) {
        this.cancellations = cancellations;
        this.allowedOrigin = allowedOrigin;
    }

    @PostMapping(path = "/api/payments/orders/{orderNumber}/refund/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Result>> cancel(@PathVariable("orderNumber") String orderNumber,
            @AuthenticationPrincipal Integer memberId, @RequestHeader(name = "Origin", required = false) String origin,
            @RequestBody JsonNode body) {
        verifyWriteRequest(memberId, origin);
        return response(cancellations.cancel(orderNumber, memberId, OrderCancellationRequest.fromJson(body)));
    }

    @PostMapping("/api/payments/orders/{orderNumber}/refund/reconcile")
    public ResponseEntity<ApiResponse<Result>> reconcile(@PathVariable("orderNumber") String orderNumber,
            @AuthenticationPrincipal Integer memberId, @RequestHeader(name = "Origin", required = false) String origin) {
        verifyWriteRequest(memberId, origin);
        return response(cancellations.reconcile(orderNumber, memberId));
    }

    private void verifyWriteRequest(Integer memberId, String origin) {
        if (memberId == null) throw new PaymentConfirmationException(401, "LOGIN_REQUIRED", "로그인이 필요합니다.");
        // 쿠키가 자동으로 전송되는 쓰기 요청은 기존 프론트 Origin과 일치할 때만 받습니다.
        if (!allowedOrigin.equals(origin)) {
            throw new PaymentConfirmationException(403, "CANCEL_ORIGIN_DENIED", "허용된 주문 화면에서 다시 요청해 주세요.");
        }
    }

    private static ResponseEntity<ApiResponse<Result>> response(Result result) {
        int status = "PENDING_CONFIRMATION".equals(result.status()) ? 202 : 200;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.ok(result));
    }

    public record ErrorDetail(String code) {}

    @ExceptionHandler(PaymentConfirmationException.class)
    public ResponseEntity<ApiResponse<ErrorDetail>> cancellationError(PaymentConfirmationException error) {
        var body = ApiResponse.<ErrorDetail>builder().success(false).message(error.getMessage())
                .data(new ErrorDetail(error.code())).build();
        return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore()).body(body);
    }

    // 잘못된 JSON이 계좌정보를 포함해도 원본 예외나 입력값을 오류 응답으로 내보내지 않습니다.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<ErrorDetail>> invalidJson() {
        return cancellationError(new PaymentConfirmationException(400, "INVALID_CANCEL_REQUEST", "취소 요청 형식을 확인해 주세요."));
    }
}
