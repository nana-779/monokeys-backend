package com.green.keyboard_project.controller;

import com.green.keyboard_project.dto.ApiResponse;
import com.green.keyboard_project.dto.payment.OrderRefundPreview;
import com.green.keyboard_project.service.payment.OrderRefundEligibilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 본인 주문의 환불 조건을 조회하는 읽기 전용 API입니다. */
@RestController
@RequiredArgsConstructor
public class OrderRefundPreviewController {
    private final OrderRefundEligibilityService eligibilityService;

    @GetMapping("/api/payments/orders/{orderNumber}/refund/eligibility")
    public ResponseEntity<ApiResponse<OrderRefundPreview>> eligibility(
            @PathVariable("orderNumber") String orderNumber, @AuthenticationPrincipal Integer memberId) {
        // 회원별 결제 조회 결과가 브라우저나 중간 캐시에 남지 않도록 합니다.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(eligibilityService.preview(orderNumber, memberId)));
    }
}
