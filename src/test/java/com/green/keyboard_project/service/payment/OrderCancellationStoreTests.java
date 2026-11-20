package com.green.keyboard_project.service.payment;

import com.green.keyboard_project.dto.payment.OrderCancellationRequest;
import com.green.keyboard_project.entity.Order;
import com.green.keyboard_project.entity.OrderItem;
import com.green.keyboard_project.entity.Payment;
import com.green.keyboard_project.entity.Product;
import com.green.keyboard_project.entity.Refund;
import com.green.keyboard_project.exceptions.PaymentConfirmationException;
import com.green.keyboard_project.repository.RefundRepository;
import com.green.keyboard_project.repository.orders.OrderDeliveryRepository;
import com.green.keyboard_project.repository.orders.OrderItemRepository;
import com.green.keyboard_project.repository.orders.OrderRepository;
import com.green.keyboard_project.repository.payment.PaymentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderCancellationStoreTests {
    private final OrderRepository orders = mock(OrderRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final RefundRepository refunds = mock(RefundRepository.class);
    private final OrderDeliveryRepository deliveries = mock(OrderDeliveryRepository.class);
    private final OrderItemRepository items = mock(OrderItemRepository.class);
    private final EntityManager em = mock(EntityManager.class);
    private final OrderCancellationStore store = new OrderCancellationStore(orders, payments, refunds, deliveries, items, em);
    private final Order order = new Order();
    private final Payment payment = new Payment();

    @Test
    void loggedOutAndUnknownOrOtherMemberRequestsDoNotReadPayments() {
        assertThatThrownBy(() -> store.inspect("ORD-1", null))
                .isInstanceOfSatisfying(PaymentConfirmationException.class, error -> assertThat(error.status()).isEqualTo(401));
        assertThatThrownBy(() -> store.inspect("ORD-1", 2))
                .isInstanceOfSatisfying(PaymentConfirmationException.class, error -> {
                    assertThat(error.status()).isEqualTo(404);
                    assertThat(error.getMessage()).isEqualTo("주문 또는 결제 정보를 찾을 수 없습니다.");
                });
        verifyNoInteractions(payments, refunds, deliveries, items, em);
    }

    @Test
    void reservationChecksPendingRowAfterPaymentLock() {
        setupPaidCard();
        var previouslyRead = store.inspect("ORD-1", 1);
        var pending = new Refund();
        pending.setStatus(Refund.RefundStatusType.REQUESTED);
        when(em.find(Payment.class, 21, LockModeType.PESSIMISTIC_WRITE)).thenReturn(payment);
        when(refunds.findByPayment_PaymentIdOrderByRequestedAtDesc(21)).thenReturn(List.of(pending));
        var request = new OrderCancellationRequest(OrderCancellationRequest.Mode.FULL, "테스트", null, 100, null);
        clearInvocations(refunds);

        assertThatThrownBy(() -> store.reserve(previouslyRead, 1, request))
                .isInstanceOfSatisfying(PaymentConfirmationException.class,
                        error -> assertThat(error.code()).isEqualTo("REFUND_IN_PROGRESS"));
        var guard = inOrder(em, refunds);
        guard.verify(em).find(Payment.class, 21, LockModeType.PESSIMISTIC_WRITE);
        guard.verify(refunds).findByPayment_PaymentIdOrderByRequestedAtDesc(21);
        verify(refunds, never()).saveAndFlush(any());
    }

    @Test
    void legacyPaidVirtualAccountRemainsBlocked() {
        setupPaidCard();
        payment.setPaymentMethod(Payment.PaymentMethodType.VIRTUAL_ACCOUNT);
        assertThatThrownBy(() -> store.inspect("ORD-1", 1))
                .isInstanceOfSatisfying(PaymentConfirmationException.class,
                        error -> assertThat(error.code()).isEqualTo("VIRTUAL_ACCOUNT_REVIEW_REQUIRED"));
        verifyNoInteractions(refunds, em);
    }

    private void setupPaidCard() {
        order.setOrderId(11);
        order.setOrderNumber("ORD-1");
        order.setFinalPayAmount(100);
        order.setOrderStatus(Order.OrderStatusType.ORDERED);
        payment.setOrder(order);
        payment.setPaymentId(21);
        payment.setPgProvider("TOSS");
        payment.setPgTid("mock-key");
        payment.setPaymentStatus(Payment.PaymentStatusType.PAID);
        payment.setPaymentMethod(Payment.PaymentMethodType.CARD);
        payment.setPaidAmount(100);
        when(orders.findByOrderNumberAndUser_UserIdAndUser_IsDeletedFalse("ORD-1", 1)).thenReturn(Optional.of(order));
        when(payments.findByOrder_OrderId(11)).thenReturn(Optional.of(payment));
        var line = new OrderItem();
        line.setProduct(new Product());
        when(items.findByOrder(order)).thenReturn(List.of(line));
    }
}
