package com.green.keyboard_project.service.payment;

import com.green.keyboard_project.dto.payment.OrderRefundPreview;
import com.green.keyboard_project.dto.payment.OrderRefundPreview.Status;
import com.green.keyboard_project.entity.Order;
import com.green.keyboard_project.entity.Order.OrderStatusType;
import com.green.keyboard_project.entity.Payment;
import com.green.keyboard_project.entity.Payment.PaymentMethodType;
import com.green.keyboard_project.entity.Payment.PaymentStatusType;
import com.green.keyboard_project.entity.Refund;
import com.green.keyboard_project.entity.Refund.RefundStatusType;
import com.green.keyboard_project.exceptions.ResourceNotFoundException;
import com.green.keyboard_project.exceptions.UnauthenticatedException;
import com.green.keyboard_project.repository.RefundRepository;
import com.green.keyboard_project.repository.orders.OrderDeliveryRepository;
import com.green.keyboard_project.repository.orders.OrderItemRepository;
import com.green.keyboard_project.repository.orders.OrderRepository;
import com.green.keyboard_project.repository.payment.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

/** 로컬 주문·결제·환불 정보만으로 사전 조건을 검토합니다. 최종 Toss 상태는 이후 실행 단계에서 확인합니다. */
@Service
@RequiredArgsConstructor
public class OrderRefundEligibilityService {
    private static final Pattern TOSS_PAYMENT_KEY = Pattern.compile("[\\x21-\\x7E]{1,200}");

    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final OrderDeliveryRepository deliveries;
    private final OrderItemRepository items;
    private final ObjectProvider<TossCancellationGateway> gateways;

    @Transactional(readOnly = true)
    public OrderRefundPreview preview(String orderNumber, Integer memberId) {
        if (memberId == null) {
            throw new UnauthenticatedException("로그인이 필요합니다.");
        }

        // 주문번호와 로그인 회원을 한 번에 조회해 없는 주문과 타인 주문을 동일한 404로 처리합니다.
        Order order = orders.findByOrderNumberAndUser_UserIdAndUser_IsDeletedFalse(orderNumber, memberId)
                .orElseThrow(() -> new ResourceNotFoundException("주문을 찾을 수 없습니다."));
        Payment payment = payments.findByOrder_OrderId(order.getOrderId()).orElse(null);
        if (payment == null) {
            return new OrderRefundPreview(order.getOrderNumber(), null, null, null, Status.BLOCKED,
                    "등록된 결제 정보가 없습니다.", false, null, null, List.of());
        }
        var orderItems = items.findByOrder(order);
        // 상품명과 단가는 주문 당시 값을 사용하고, 썸네일은 연결된 현재 상품에서 읽습니다.
        var previewItems = orderItems.stream()
                .map(item -> new OrderRefundPreview.Item(item.getProductNameSnapshot(), item.getOrderedPrice(),
                        item.getQuantity(), item.getProduct() == null ? null : item.getProduct().getThumbnailUrl()))
                .toList();

        List<Refund> history = refunds.findByPayment_PaymentIdOrderByRequestedAtDesc(payment.getPaymentId());
        // 완료된 금액만 차감합니다. 진행 중인 요청은 금액 확정 대신 중복 요청 제한에 사용합니다.
        long completedAmount = history.stream()
                .filter(refund -> refund.getStatus() == RefundStatusType.COMPLETED)
                .mapToLong(Refund::getAmount)
                .sum();
        int paidAmount = payment.getPaidAmount();
        Integer remainingAmount = completedAmount >= 0 && completedAmount <= paidAmount
                ? paidAmount - (int) completedAmount : null;
        boolean refundInProgress = history.stream()
                .anyMatch(refund -> refund.getStatus() == RefundStatusType.REQUESTED);
        Status status = Status.BLOCKED;
        String message;

        // 토스 시크릿을 읽거나 PG에 연결하지 않고, 저장된 거래 키와 로컬 결제 상태만 확인합니다.
        if (!"TOSS".equals(payment.getPgProvider()) || payment.getPgTid() == null
                || !TOSS_PAYMENT_KEY.matcher(payment.getPgTid()).matches()) {
            message = "저장된 Toss 결제 키를 확인할 수 없습니다.";
        } else if (payment.getPaymentStatus() == PaymentStatusType.CANCELLED
                || order.getOrderStatus() == OrderStatusType.CANCELLED) {
            message = "이미 취소된 주문 또는 결제입니다.";
        } else if (refundInProgress) {
            message = "처리 중인 환불 요청이 있습니다.";
        } else if (paidAmount <= 0 || paidAmount != order.getFinalPayAmount() || remainingAmount == null) {
            message = "주문 금액과 결제·환불 기록을 확인해야 합니다.";
        } else if (remainingAmount == 0) {
            message = "이미 전액 환불된 결제입니다.";
        } else if (completedAmount > 0 || payment.getCancelledAt() != null) {
            status = Status.POLICY_REVIEW_REQUIRED;
            message = "이전 취소 내역이 있는 결제는 부분 환불 정책과 Toss 잔액을 확인해야 합니다.";
        } else if (payment.getPaymentMethod() == PaymentMethodType.VIRTUAL_ACCOUNT) {
            status = Status.POLICY_REVIEW_REQUIRED;
            message = "가상계좌는 실제 입금 상태와 환불계좌 정책을 확인해야 합니다.";
        } else if (payment.getPaymentStatus() != PaymentStatusType.PAID) {
            message = "결제 완료 상태가 아닙니다.";
        } else if (order.getOrderStatus() != OrderStatusType.ORDERED || payment.getPaymentMethod() == null) {
            status = Status.POLICY_REVIEW_REQUIRED;
            message = "현재 주문 상태와 결제수단의 취소 조건을 확인해야 합니다.";
        } else if (deliveries.existsByOrder_OrderId(order.getOrderId())) {
            status = Status.POLICY_REVIEW_REQUIRED;
            message = "배송 기록이 있어 배송 상태별 취소 정책을 확인해야 합니다.";
        } else {
            // 주문 품목이 없거나 조립 품목이 섞인 주문에는 일반상품 전액 환불 기준을 적용하지 않습니다.
            if (orderItems.isEmpty()) {
                message = "환불 대상을 확인할 주문 품목이 없습니다.";
            } else if (orderItems.stream().anyMatch(item -> item.getProduct() == null
                    || item.getManualBuild() != null || item.getAutoBuild() != null)) {
                status = Status.POLICY_REVIEW_REQUIRED;
                message = "조립상품이 포함된 주문은 취소 정책을 확인해야 합니다.";
            } else {
                status = Status.PRECHECK_PASSED;
                message = "로컬 사전 조건을 충족했습니다. 실제 요청 시 Toss 결제 상태를 확인합니다.";
            }
        }

        // 화면에는 실행 가능 여부만 전달합니다. 사전 조회 중에는 결제사에 접속하지 않습니다.
        return new OrderRefundPreview(order.getOrderNumber(), paidAmount, payment.getPaymentStatus(),
                remainingAmount, status, message, gateways.getIfAvailable() != null,
                payment.getPgProvider(), payment.getPaymentMethod(), previewItems);
    }
}
