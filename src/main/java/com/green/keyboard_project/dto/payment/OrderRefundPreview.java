package com.green.keyboard_project.dto.payment;

import com.green.keyboard_project.entity.Payment.PaymentStatusType;
import com.green.keyboard_project.entity.Payment.PaymentMethodType;

import java.util.List;

/** 본인 주문의 로컬 사전 확인 결과입니다. 결제 키와 회원 개인정보는 포함하지 않습니다. */
public record OrderRefundPreview(
        String orderNumber,
        Integer paymentAmount,
        PaymentStatusType paymentStatus,
        Integer localRemainingAmount,
        Status status,
        String message,
        boolean cancellationEnabled,
        String pgProvider,
        PaymentMethodType paymentMethod,
        List<Item> items
) {
    /** 주문 당시 상품 정보와 현재 상품의 썸네일을 취소 확인 화면에 전달합니다. */
    public record Item(String productName, int unitPrice, int quantity, String thumbnailUrl) {}

    public enum Status {
        PRECHECK_PASSED, BLOCKED, POLICY_REVIEW_REQUIRED
    }
}
