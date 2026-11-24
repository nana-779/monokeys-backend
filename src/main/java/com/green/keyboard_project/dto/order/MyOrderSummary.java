package com.green.keyboard_project.dto.order;

import com.green.keyboard_project.entity.Order.OrderStatusType;
import com.green.keyboard_project.entity.Payment.PaymentStatusType;

import java.time.LocalDateTime;

/** 마이페이지에 필요한 주문 정보만 담습니다. 결제 기록이 없으면 금액과 결제 상태는 null입니다. */
public record MyOrderSummary(
        String orderNumber,
        LocalDateTime orderedAt,
        Integer paymentAmount,
        OrderStatusType orderStatus,
        PaymentStatusType paymentStatus
) {
}
