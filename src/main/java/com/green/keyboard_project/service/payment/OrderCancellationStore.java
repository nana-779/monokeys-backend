package com.green.keyboard_project.service.payment;

import static com.green.keyboard_project.entity.OrderDelivery.DeliveryStatusType.PREPARING;

import com.green.keyboard_project.dto.payment.OrderCancellationRequest;
import com.green.keyboard_project.entity.Order;
import com.green.keyboard_project.entity.Order.OrderStatusType;
import com.green.keyboard_project.entity.Payment;
import com.green.keyboard_project.entity.Payment.PaymentMethodType;
import com.green.keyboard_project.entity.Payment.PaymentStatusType;
import com.green.keyboard_project.entity.Refund;
import com.green.keyboard_project.entity.Refund.RefundStatusType;
import com.green.keyboard_project.exceptions.PaymentConfirmationException;
import com.green.keyboard_project.repository.RefundRepository;
import com.green.keyboard_project.repository.orders.OrderDeliveryRepository;
import com.green.keyboard_project.repository.orders.OrderItemRepository;
import com.green.keyboard_project.repository.orders.OrderRepository;
import com.green.keyboard_project.repository.payment.PaymentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/** 기존 환불 테이블을 사용해 외부 호출 전후의 짧은 트랜잭션만 관리합니다. */
@Service
@RequiredArgsConstructor
public class OrderCancellationStore {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final OrderDeliveryRepository deliveries;
    private final OrderItemRepository items;
    private final EntityManager entityManager;

    record Target(int paymentId, String orderNumber, String paymentKey, int total, int remaining) {
        @Override public String toString() { return "CancellationTarget[redacted]"; }
    }

    record Pending(Target target, int refundId, int amount, String reason) {
        @Override public String toString() { return "PendingCancellation[redacted]"; }
    }

    @Transactional(readOnly = true)
    public Target inspect(String orderNumber, Integer memberId) {
        Order order = ownedOrder(orderNumber, memberId);
        Payment payment = payments.findByOrder_OrderId(order.getOrderId()).orElseThrow(OrderCancellationStore::missing);
        return target(order, payment);
    }

    // 응답이 끊겨 요청 번호를 받지 못해도 본인 주문에서 진행 중인 요청을 찾아 대사할 수 있습니다.
    @Transactional(readOnly = true)
    public Pending pendingForRecovery(String orderNumber, Integer memberId) {
        Order order = ownedOrder(orderNumber, memberId);
        Payment payment = payments.findByOrder_OrderId(order.getOrderId()).orElseThrow(OrderCancellationStore::missing);
        String key = paymentKey(order, payment);
        var history = refunds.findByPayment_PaymentIdOrderByRequestedAtDesc(payment.getPaymentId());
        var inProgress = history.stream().filter(row -> row.getStatus() == RefundStatusType.REQUESTED).toList();
        int remaining = remainingBalance(payment.getPaidAmount(), history);
        if (inProgress.size() != 1 || inProgress.getFirst().getAmount() <= 0
                || inProgress.getFirst().getAmount() > remaining) {
            throw conflict("REFUND_STATE_CHANGED", "대사할 환불 요청을 한 건으로 확인할 수 없습니다.");
        }
        Refund refund = inProgress.getFirst();
        return new Pending(new Target(payment.getPaymentId(), order.getOrderNumber(), key,
                payment.getPaidAmount(), remaining), refund.getRefundId(), refund.getAmount(), refund.getReason());
    }

    // 결제 행을 잠근 뒤 REQUESTED를 먼저 커밋합니다. 다음 요청은 PG 호출 중에도 DB의 진행 상태를 확인할 수 있습니다.
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public int reserve(Target expected, Integer memberId, OrderCancellationRequest request) {
        Payment payment = lock(expected.paymentId());
        Target current = target(ownedOrder(expected.orderNumber(), memberId), payment);
        if (!expected.equals(current)) {
            throw conflict("REFUND_BALANCE_CHANGED", "결제 정보가 변경됐습니다. 다시 확인해 주세요.");
        }
        var refund = new Refund();
        refund.setPayment(payment);
        refund.setStatus(RefundStatusType.REQUESTED);
        refund.setAmount(request.amountFor(current.remaining()));
        refund.setReason(request.cancelReason());
        refund.setRequestedAt(LocalDateTime.now(KOREA));
        return refunds.saveAndFlush(refund).getRefundId();
    }

    // 확인된 전액 취소는 주문·결제·환불 상태를 같은 트랜잭션에서 저장합니다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(int paymentId, int refundId, TossCancellationGateway.Cancellation cancellation) {
        Refund refund = lockedRefund(paymentId, refundId);
        if (refund.getAmount() != cancellation.amount()) {
            throw conflict("REFUND_AMOUNT_MISMATCH", "취소 거래 금액을 다시 확인해야 합니다.");
        }
        // 같은 거래의 대사가 겹쳐도 이미 확정한 환불 행을 다시 쓰지 않습니다.
        if (refund.getStatus() == RefundStatusType.COMPLETED
                && cancellation.transactionKey().equals(refund.getPgCancelTid())) return;
        requirePending(refund);
        // 결제 행 잠금에 더해 DB의 거래 키 UNIQUE가 서로 다른 결제의 중복 저장도 막습니다.
        if (refunds.existsByPgCancelTid(cancellation.transactionKey())) {
            throw conflict("REFUND_TRANSACTION_DUPLICATE", "이미 기록된 취소 거래입니다.");
        }
        Payment payment = refund.getPayment();
        int remaining = remainingBalance(payment.getPaidAmount(),
                refunds.findByPayment_PaymentIdOrderByRequestedAtDesc(paymentId));
        if (payment.getPaymentStatus() != PaymentStatusType.PAID
                || cancellation.amount() <= 0 || cancellation.amount() > remaining) {
            throw conflict("REFUND_STATE_CHANGED", "결제 상태 또는 남은 금액을 다시 확인해 주세요.");
        }
        LocalDateTime canceledAt = cancellation.canceledAt().atZoneSameInstant(KOREA).toLocalDateTime();
        refund.setPgCancelTid(cancellation.transactionKey());
        refund.setRefundedAt(canceledAt);
        refund.setStatus(RefundStatusType.COMPLETED);
        // 결제 잔액을 모두 취소한 경우에만 주문과 결제를 취소 완료로 바꿉니다.
        if (cancellation.amount() == remaining) {
            payment.setPaymentStatus(PaymentStatusType.CANCELLED);
            payment.setCancelledAt(canceledAt);
            payment.getOrder().setOrderStatus(OrderStatusType.CANCELLED);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reject(int paymentId, int refundId) {
        Refund refund = lockedRefund(paymentId, refundId);
        requirePending(refund);
        refund.setStatus(RefundStatusType.REJECTED);
    }

    private Refund lockedRefund(int paymentId, int refundId) {
        lock(paymentId);
        return refunds.findById(refundId)
                .filter(refund -> refund.getPayment().getPaymentId() == paymentId)
                .orElseThrow(() -> conflict("REFUND_STATE_CHANGED", "환불 요청 상태를 다시 확인해 주세요."));
    }

    private static void requirePending(Refund refund) {
        if (refund.getStatus() != RefundStatusType.REQUESTED) {
            throw conflict("REFUND_STATE_CHANGED", "환불 요청 상태를 다시 확인해 주세요.");
        }
    }

    private Payment lock(int paymentId) {
        Payment payment = entityManager.find(Payment.class, paymentId, LockModeType.PESSIMISTIC_WRITE);
        if (payment == null) throw missing();
        return payment;
    }

    private Order ownedOrder(String number, Integer memberId) {
        if (memberId == null) throw new PaymentConfirmationException(401, "LOGIN_REQUIRED", "로그인이 필요합니다.");
        // 타인의 주문, 탈퇴한 회원의 주문, 없는 주문을 같은 응답으로 처리합니다.
        return orders.findByOrderNumberAndUser_UserIdAndUser_IsDeletedFalse(number, memberId)
                .orElseThrow(OrderCancellationStore::missing);
    }

    private Target target(Order order, Payment payment) {
        String key = paymentKey(order, payment);
        if (payment.getPaymentMethod() == PaymentMethodType.VIRTUAL_ACCOUNT) {
            // 현재 승인 코드는 입금 대기도 PAID로 저장하므로 회원 API에서는 두 상태를 구분할 수 없습니다.
            throw conflict("VIRTUAL_ACCOUNT_REVIEW_REQUIRED", "가상계좌 입금 상태 확인이 필요합니다.");
        }
        if (payment.getPaymentStatus() != PaymentStatusType.PAID || order.getOrderStatus() != OrderStatusType.ORDERED
                || payment.getPaymentMethod() == null || payment.getCancelledAt() != null
                || payment.getPaidAmount() <= 0 || payment.getPaidAmount() != order.getFinalPayAmount()) {
            throw conflict("INVALID_PAYMENT_STATE", "현재 주문 또는 결제 상태에서 취소할 수 없습니다.");
        }

        var history = refunds.findByPayment_PaymentIdOrderByRequestedAtDesc(payment.getPaymentId());
        if (history.stream().anyMatch(row -> row.getStatus() == RefundStatusType.REQUESTED)) {
            throw conflict("REFUND_IN_PROGRESS", "처리 중인 환불의 결과를 먼저 확인해 주세요.");
        }
        int remaining = remainingBalance(payment.getPaidAmount(), history);
        if (deliveries.existsByOrder_OrderIdAndDeliveryStatusNot(order.getOrderId(), PREPARING)) {
            throw conflict("DELIVERY_REVIEW_REQUIRED", "발송된 주문은 취소할 수 없습니다.");
        }
        var lines = items.findByOrder(order);
        if (lines.isEmpty() || lines.stream().anyMatch(line -> line.getProduct() == null
                || line.getManualBuild() != null || line.getAutoBuild() != null)) {
            throw conflict("ORDER_ITEMS_REVIEW_REQUIRED", "주문 품목의 취소 정책을 확인해야 합니다.");
        }
        return new Target(payment.getPaymentId(), order.getOrderNumber(), key,
                payment.getPaidAmount(), remaining);
    }

    private static String paymentKey(Order order, Payment payment) {
        if (payment.getOrder().getOrderId() != order.getOrderId()) throw missing();
        String key = payment.getPgTid();
        if (!"TOSS".equals(payment.getPgProvider()) || key == null || !key.matches("[\\x21-\\x7E]{1,200}")) {
            throw conflict("INVALID_STORED_PAYMENT", "저장된 Toss 결제 정보를 확인해 주세요.");
        }
        return key;
    }

    private static int remainingBalance(int paidAmount, List<Refund> history) {
        // 확정 완료 금액만 합산하며, 결제금액을 소진한 내역은 추가 취소를 제한합니다.
        long refunded = history.stream().filter(row -> row.getStatus() == RefundStatusType.COMPLETED)
                .mapToLong(Refund::getAmount).sum();
        if (refunded < 0 || refunded >= paidAmount) {
            throw conflict("NO_REFUND_BALANCE", "남은 취소 가능 금액을 확인할 수 없습니다.");
        }
        return paidAmount - (int) refunded;
    }

    private static PaymentConfirmationException missing() {
        return new PaymentConfirmationException(404, "ORDER_PAYMENT_NOT_FOUND", "주문 또는 결제 정보를 찾을 수 없습니다.");
    }

    private static PaymentConfirmationException conflict(String code, String message) {
        return new PaymentConfirmationException(409, code, message);
    }
}
