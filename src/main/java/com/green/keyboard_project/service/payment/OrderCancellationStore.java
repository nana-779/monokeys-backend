package com.green.keyboard_project.service.payment;

import static com.green.keyboard_project.entity.OrderDelivery.DeliveryStatusType.DELIVERED;
import static com.green.keyboard_project.entity.OrderDelivery.DeliveryStatusType.PREPARING;
import static com.green.keyboard_project.entity.OrderDelivery.DeliveryStatusType.RETURNED;

import com.green.keyboard_project.dto.payment.OrderCancellationRequest;
import com.green.keyboard_project.entity.Order;
import com.green.keyboard_project.entity.OrderDelivery;
import com.green.keyboard_project.service.CartService;
import com.green.keyboard_project.entity.Order.OrderStatusType;
import com.green.keyboard_project.entity.Payment;
import com.green.keyboard_project.entity.Payment.PaymentMethodType;
import com.green.keyboard_project.entity.Payment.PaymentStatusType;
import com.green.keyboard_project.entity.Refund;
import com.green.keyboard_project.entity.Refund.RefundStatusType;
import com.green.keyboard_project.entity.Refund.RequestType;
import com.green.keyboard_project.entity.Refund.ReturnReasonType;
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

import java.time.LocalDate;
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
    private final CartService cartService;

    record Target(int paymentId, String orderNumber, String paymentKey, int total, int remaining) {
        @Override public String toString() { return "CancellationTarget[redacted]"; }
    }

    record Pending(Target target, int refundId, int amount, String reason) {
        @Override public String toString() { return "PendingCancellation[redacted]"; }
    }

    public record WorkflowRequest(RequestType type, String reason, ReturnReasonType returnReason) {}

    public record WorkflowView(Integer id, String orderNumber, RefundStatusType status, RequestType type,
                               int amount, String reason, ReturnReasonType returnReason, String rejectionReason,
                               LocalDateTime requestedAt, RequestType availableType) {}

    @Transactional(readOnly = true)
    public WorkflowView workflow(String orderNumber, Integer memberId) {
        Order order = ownedOrder(orderNumber, memberId);
        Payment payment = payments.findByOrder_OrderId(order.getOrderId()).orElse(null);
        if (payment == null) return view(order, null, null);
        var history = refunds.findByPayment_PaymentIdOrderByRequestedAtDesc(payment.getPaymentId());
        Refund latest = history.stream().filter(row -> row.getRequestType() != null
                || row.getStatus() == RefundStatusType.REQUESTED).findFirst().orElse(null);
        RequestType available = null;
        try {
            RequestType type = deliveries.findByOrder_OrderId(order.getOrderId())
                    .filter(delivery -> delivery.getDeliveryStatus() == DELIVERED)
                    .map(delivery -> RequestType.RETURN).orElse(RequestType.CANCELLATION);
            workflowTarget(order, payment, type, null);
            available = type;
        } catch (PaymentConfirmationException ignored) {
            // 주문 상태가 신청 조건을 충족할 때만 신청 유형을 노출합니다.
        }
        return view(order, latest, available);
    }

    @Transactional(readOnly = true)
    public List<WorkflowView> workflowQueue() {
        return refunds.findByRequestTypeIsNotNullOrderByRequestedAtDescRefundIdDesc().stream()
                .map(row -> view(row.getPayment().getOrder(), row, null)).toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WorkflowView requestWorkflow(String orderNumber, Integer memberId, WorkflowRequest request) {
        Order order = ownedOrder(orderNumber, memberId);
        Payment payment = lock(payments.findByOrder_OrderId(order.getOrderId())
                .orElseThrow(OrderCancellationStore::missing).getPaymentId());
        Target target = workflowTarget(order, payment, request.type(), null);
        String reason = checkedReason(request.reason(), 140);
        if (request.type() == RequestType.RETURN) validateReturnDates(order, request);
        else if (request.returnReason() != null) {
            throw invalidWorkflow("배송 전 취소에는 반품 정보를 받을 수 없습니다.");
        }
        var row = new Refund();
        row.setPayment(payment);
        row.setAmount(target.total());
        row.setReason(reason);
        row.setRequestedAt(LocalDateTime.now(KOREA));
        row.setStatus(RefundStatusType.PENDING_APPROVAL);
        row.setRequestType(request.type());
        row.setReturnReasonType(request.returnReason());
        return view(order, refunds.saveAndFlush(row), null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void withdrawWorkflow(String orderNumber, Integer memberId, int id) {
        Order order = ownedOrder(orderNumber, memberId);
        Payment payment = payments.findByOrder_OrderId(order.getOrderId()).orElseThrow(OrderCancellationStore::missing);
        Refund row = lockedRefund(payment.getPaymentId(), id);
        requireWorkflowStatus(row, RefundStatusType.PENDING_APPROVAL);
        row.setStatus(RefundStatusType.WITHDRAWN);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void rejectWorkflow(int id, String reason) {
        Refund row = lockWorkflow(id);
        if (row.getStatus() != RefundStatusType.AWAITING_RETURN) requireWorkflowStatus(row, RefundStatusType.PENDING_APPROVAL);
        row.setRejectionReason(checkedReason(reason, 255));
        row.setStatus(RefundStatusType.REJECTED);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void holdReturn(int id) {
        Refund row = lockWorkflow(id);
        requireWorkflowStatus(row, RefundStatusType.PENDING_APPROVAL);
        if (row.getRequestType() != RequestType.RETURN) throw invalidWorkflow("반품 요청만 회수 대기로 바꿀 수 있습니다.");
        Payment payment = row.getPayment();
        workflowTarget(payment.getOrder(), payment, RequestType.RETURN, id);
        row.setStatus(RefundStatusType.AWAITING_RETURN);
    }

    @Transactional(readOnly = true)
    public Pending inspectWorkflow(int id, boolean returned) {
        Refund row = refunds.findById(id).orElseThrow(OrderCancellationStore::missing);
        checkExecutionStatus(row, returned);
        Payment payment = row.getPayment();
        return new Pending(workflowTarget(payment.getOrder(), payment, row.getRequestType(), id),
                id, row.getAmount(), row.getReason());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public int reserveWorkflow(Pending expected, boolean returned) {
        Refund row = lockedRefund(expected.target().paymentId(), expected.refundId());
        checkExecutionStatus(row, returned);
        Payment payment = row.getPayment();
        Target current = workflowTarget(payment.getOrder(), payment, row.getRequestType(), row.getRefundId());
        if (!expected.target().equals(current) || row.getAmount() != current.total()) {
            throw conflict("REFUND_BALANCE_CHANGED", "결제 정보가 변경됐습니다. 다시 확인해 주세요.");
        }
        row.setStatus(RefundStatusType.REQUESTED);
        return row.getRefundId();
    }

    private Target workflowTarget(Order order, Payment payment, RequestType type, Integer ignoredId) {
        if (type == null) throw invalidWorkflow("요청 종류를 선택해 주세요.");
        String key = paymentKey(order, payment);
        if (payment.getPaymentStatus() != PaymentStatusType.PAID || payment.getPaymentMethod() == null
                || payment.getPaymentMethod() == PaymentMethodType.VIRTUAL_ACCOUNT || payment.getCancelledAt() != null
                || payment.getPaidAmount() <= 0 || payment.getPaidAmount() != order.getFinalPayAmount()
                || (order.getOrderStatus() != OrderStatusType.ORDERED && order.getOrderStatus() != OrderStatusType.CONFIRMED)) {
            throw conflict("INVALID_PAYMENT_STATE", "현재 주문 또는 결제 상태에서 요청할 수 없습니다.");
        }
        var history = refunds.findByPayment_PaymentIdOrderByRequestedAtDesc(payment.getPaymentId());
        if (history.stream().anyMatch(row -> !java.util.Objects.equals(row.getRefundId(), ignoredId)
                && (row.getStatus() == RefundStatusType.PENDING_APPROVAL || row.getStatus() == RefundStatusType.AWAITING_RETURN
                || row.getStatus() == RefundStatusType.REQUESTED))) {
            throw conflict("REFUND_IN_PROGRESS", "이미 진행 중인 취소 또는 반품 요청이 있습니다.");
        }
        int remaining = remainingBalance(payment.getPaidAmount(), history);
        if (remaining != payment.getPaidAmount()) throw conflict("REFUND_BALANCE_CHANGED", "전액 환불 가능 금액을 확인해 주세요.");
        var delivery = deliveries.findByOrder_OrderId(order.getOrderId()).orElseThrow(OrderCancellationStore::missing);
        if (type == RequestType.CANCELLATION && !beforeShipment(delivery)) {
            throw conflict("DELIVERY_REVIEW_REQUIRED", "배송 준비 중인 주문만 취소 요청할 수 있습니다.");
        }
        if (type == RequestType.RETURN && (delivery.getDeliveryStatus() != DELIVERED || delivery.getReceiptConfirmedAt() == null)) {
            throw conflict("RECEIPT_REQUIRED", "직접 수령 확인한 주문만 반품을 요청할 수 있습니다.");
        }
        var lines = items.findByOrder(order);
        if (lines.isEmpty()) {
            throw conflict("ORDER_ITEMS_REVIEW_REQUIRED", "주문 품목의 환불 또는 재고 정책을 확인해야 합니다.");
        }
        return new Target(payment.getPaymentId(), order.getOrderNumber(), key, payment.getPaidAmount(), remaining);
    }

    private static boolean beforeShipment(OrderDelivery delivery) {
        return delivery.getDeliveryStatus() == PREPARING
                && (delivery.getTrackingNumber() == null || delivery.getTrackingNumber().isBlank());
    }

    private void validateReturnDates(Order order, WorkflowRequest request) {
        var delivery = deliveries.findByOrder_OrderId(order.getOrderId()).orElseThrow(OrderCancellationStore::missing);
        if (request.returnReason() == null) throw invalidWorkflow("반품 사유를 선택해 주세요.");
        int days = request.returnReason() == ReturnReasonType.CHANGE_OF_MIND ? 7 : 30;
        LocalDate deadline = delivery.getReceiptConfirmedAt().toLocalDate().plusDays(days);
        if (LocalDate.now(KOREA).isAfter(deadline)) throw invalidWorkflow("수령 확인 후 반품 신청 기한이 지났습니다.");
    }

    private Refund lockWorkflow(int id) {
        Refund row = refunds.findById(id).orElseThrow(OrderCancellationStore::missing);
        lock(row.getPayment().getPaymentId());
        entityManager.refresh(row);
        return row;
    }

    private static void checkExecutionStatus(Refund row, boolean returned) {
        if (!returned || row.getStatus() != RefundStatusType.AWAITING_RETURN) requireWorkflowStatus(row, RefundStatusType.PENDING_APPROVAL);
        if ((row.getRequestType() == RequestType.RETURN) != returned) {
            throw invalidWorkflow("요청 종류에 맞는 관리자 동작을 선택해 주세요.");
        }
    }

    private static void requireWorkflowStatus(Refund row, RefundStatusType status) {
        if (row.getRequestType() == null || row.getStatus() != status) {
            throw conflict("REFUND_STATE_CHANGED", "이미 처리되었거나 현재 상태에서 변경할 수 없는 요청입니다.");
        }
    }

    private static String checkedReason(String reason, int limit) {
        if (reason == null || reason.isBlank() || reason.strip().length() > limit || reason.codePoints().anyMatch(Character::isISOControl)) {
            throw invalidWorkflow("사유를 " + limit + "자 이내로 입력해 주세요.");
        }
        return reason.strip();
    }

    private static PaymentConfirmationException invalidWorkflow(String message) {
        return new PaymentConfirmationException(400, "INVALID_REFUND_REQUEST", message);
    }

    private WorkflowView view(Order order, Refund row, RequestType available) {
        if (row == null) return new WorkflowView(null, order.getOrderNumber(), null, null, 0, null, null, null, null, available);
        return new WorkflowView(row.getRefundId(), order.getOrderNumber(), row.getStatus(), row.getRequestType(),
                row.getAmount(), row.getReason(), row.getReturnReasonType(), row.getRejectionReason(), row.getRequestedAt(), available);
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
        return pendingForPayment(order, payment, null);
    }

    @Transactional(readOnly = true)
    public Pending pendingWorkflow(int id) {
        Refund row = refunds.findById(id).orElseThrow(OrderCancellationStore::missing);
        requireWorkflowStatus(row, RefundStatusType.REQUESTED);
        Payment payment = row.getPayment();
        return pendingForPayment(payment.getOrder(), payment, id);
    }

    private Pending pendingForPayment(Order order, Payment payment, Integer expectedId) {
        String key = paymentKey(order, payment);
        var history = refunds.findByPayment_PaymentIdOrderByRequestedAtDesc(payment.getPaymentId());
        var inProgress = history.stream().filter(row -> row.getStatus() == RefundStatusType.REQUESTED).toList();
        int remaining = remainingBalance(payment.getPaidAmount(), history);
        if (inProgress.size() != 1 || (expectedId != null && inProgress.getFirst().getRefundId() != expectedId)
                || inProgress.getFirst().getAmount() <= 0
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
            
            Order order = payment.getOrder();
            order.setOrderStatus(OrderStatusType.CANCELLED);
            if (refund.getRequestType() == RequestType.CANCELLATION) {
                var delivery = deliveries.findByOrder_OrderId(order.getOrderId()).orElseThrow(OrderCancellationStore::missing);
                if (!beforeShipment(delivery)) {
                    throw conflict("DELIVERY_REVIEW_REQUIRED", "발송 상태를 확인해야 재고를 복구할 수 있습니다.");
                }
                cartService.restoreStockForOrderItems(items.findByOrder(order));
                delivery.setDeliveryStatus(OrderDelivery.DeliveryStatusType.CANCELLED);
            } else if (refund.getRequestType() == RequestType.RETURN) {
                order.setOrderStatus(OrderStatusType.REFUNDED);
                deliveries.findByOrder_OrderId(order.getOrderId()).orElseThrow(OrderCancellationStore::missing)
                        .setDeliveryStatus(RETURNED);
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reject(int paymentId, int refundId) {
        Refund refund = lockedRefund(paymentId, refundId);
        requirePending(refund);
        refund.setStatus(RefundStatusType.REJECTED);
        if (refund.getRequestType() != null) refund.setRejectionReason("결제사에서 환불을 거절했습니다.");
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
