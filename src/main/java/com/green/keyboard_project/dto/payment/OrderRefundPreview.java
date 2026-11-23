package com.green.keyboard_project.dto.payment;

import com.green.keyboard_project.entity.Payment.PaymentStatusType;

/** 본인 주문의 로컬 사전 확인 결과입니다. 결제 키와 회원 개인정보는 포함하지 않습니다. */
public record OrderRefundPreview(
        String orderNumber,
        Integer paymentAmount,
        PaymentStatusType paymentStatus,
        Integer localRemainingAmount,
        Status status,
        String message,
        boolean cancellationEnabled
) {
    public enum Status {
        PRECHECK_PASSED, BLOCKED, POLICY_REVIEW_REQUIRED
    }
}
