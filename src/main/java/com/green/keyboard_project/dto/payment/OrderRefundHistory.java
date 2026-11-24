package com.green.keyboard_project.dto.payment;

import com.green.keyboard_project.entity.Refund.RefundStatusType;

import java.time.OffsetDateTime;
import java.util.List;

/** 주문 소유자에게 표시할 로컬 환불 내역입니다. 결제 키, 취소 거래 키, 환불계좌는 반환하지 않습니다. */
public record OrderRefundHistory(String orderNumber, Integer paymentAmount, Integer completedAmount,
                                 Integer remainingAmount, Summary status, List<Entry> entries) {
    public enum Summary { NO_PAYMENT, NONE, PARTIAL, FULL, REVIEW_REQUIRED }

    public enum Effect { PARTIAL, FULL }

    public record Entry(int id, int amount, String reason, OffsetDateTime requestedAt,
                        OffsetDateTime canceledAt, RefundStatusType status, Effect effect) {}
}
