package com.green.keyboard_project.dto.order;

import com.green.keyboard_project.entity.Order.OrderStatusType;
import com.green.keyboard_project.entity.OrderDelivery.DeliveryStatusType;
import com.green.keyboard_project.entity.Payment.PaymentStatusType;

import java.time.LocalDateTime;
import java.util.List;

/** 마이페이지에 필요한 주문 정보만 담습니다. 결제 기록이 없으면 금액과 결제 상태는 null입니다. */
public record MyOrderSummary(
        String orderNumber,
        LocalDateTime orderedAt,
        Integer paymentAmount,
        OrderStatusType orderStatus,
        PaymentStatusType paymentStatus,
        DeliveryStatusType deliveryStatus,
        List<Item> items
) {
    /** 주문 당시의 상품명과 단가, 현재 상품의 썸네일을 반환합니다. 썸네일은 없을 수 있습니다. */
    public record Item(int orderItemId, String productName, int unitPrice, int quantity, String thumbnailUrl) {}

    /** 주문·결제·배송을 한 번에 조회하는 내부 결과입니다. 품목 조회에만 내부 주문 ID를 사용합니다. */
    public record Header(int orderId, String orderNumber, LocalDateTime orderedAt, Integer paymentAmount,
                         OrderStatusType orderStatus, PaymentStatusType paymentStatus, DeliveryStatusType deliveryStatus) {
        public MyOrderSummary withItems(List<Item> items) {
            return new MyOrderSummary(orderNumber, orderedAt, paymentAmount, orderStatus, paymentStatus, deliveryStatus, items);
        }
    }
}
