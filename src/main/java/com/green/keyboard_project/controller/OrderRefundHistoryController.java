package com.green.keyboard_project.controller;

import com.green.keyboard_project.dto.ApiResponse;
import com.green.keyboard_project.dto.payment.OrderRefundHistory;
import com.green.keyboard_project.service.payment.OrderRefundHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 본인 주문의 환불 거래와 로컬 기준 잔액을 조회합니다. */
@RestController
@RequiredArgsConstructor
public class OrderRefundHistoryController {
    private final OrderRefundHistoryService histories;

    @GetMapping("/api/payments/orders/{orderNumber}/refund/history")
    public ResponseEntity<ApiResponse<OrderRefundHistory>> history(
            @PathVariable("orderNumber") String orderNumber, @AuthenticationPrincipal Integer memberId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(histories.history(orderNumber, memberId)));
    }
}
