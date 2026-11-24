package com.green.keyboard_project.service.payment;

import static com.green.keyboard_project.entity.OrderDelivery.DeliveryStatusType.PREPARING;

import com.green.keyboard_project.dto.payment.OrderRefundPreview.Item;
import com.green.keyboard_project.dto.payment.OrderRefundPreview.Status;
import com.green.keyboard_project.entity.ManualBuild;
import com.green.keyboard_project.entity.Order;
import com.green.keyboard_project.entity.Order.OrderStatusType;
import com.green.keyboard_project.entity.OrderDelivery;
import com.green.keyboard_project.entity.OrderItem;
import com.green.keyboard_project.entity.Payment;
import com.green.keyboard_project.entity.Payment.PaymentMethodType;
import com.green.keyboard_project.entity.Payment.PaymentStatusType;
import com.green.keyboard_project.entity.Product;
import com.green.keyboard_project.entity.Refund;
import com.green.keyboard_project.entity.Refund.RefundStatusType;
import com.green.keyboard_project.exceptions.ResourceNotFoundException;
import com.green.keyboard_project.exceptions.UnauthenticatedException;
import com.green.keyboard_project.repository.RefundRepository;
import com.green.keyboard_project.repository.orders.OrderDeliveryRepository;
import com.green.keyboard_project.repository.orders.OrderItemRepository;
import com.green.keyboard_project.repository.orders.OrderRepository;
import com.green.keyboard_project.repository.payment.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.repository.query.parser.PartTree;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** DB나 Toss에 연결하지 않고 사전 조회의 인증, 금액, 상태 분기를 검증합니다. */
class OrderRefundEligibilityServiceTests {
    private static final String ORDER_NUMBER = "ORD-TEST";
    private static final int MEMBER_ID = 7;
    private final OrderRepository orders = mock(OrderRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final RefundRepository refunds = mock(RefundRepository.class);
    private final OrderDeliveryRepository deliveries = mock(OrderDeliveryRepository.class);
    private final OrderItemRepository items = mock(OrderItemRepository.class);
    private final OrderRefundEligibilityService service =
            new OrderRefundEligibilityService(orders, payments, refunds, deliveries, items,
                    new StaticListableBeanFactory().getBeanProvider(TossCancellationGateway.class));
    private final Order order = new Order();
    private final Payment payment = new Payment();

    @BeforeEach
    void setUp() {
        order.setOrderId(11);
        order.setOrderNumber(ORDER_NUMBER);
        order.setOrderStatus(OrderStatusType.ORDERED);
        order.setFinalPayAmount(10_000);
        payment.setPaymentId(21);
        payment.setPgProvider("TOSS");
        payment.setPgTid("key-for-local-test");
        payment.setPaidAmount(10_000);
        payment.setPaymentStatus(PaymentStatusType.PAID);
        payment.setPaymentMethod(PaymentMethodType.CARD);
    }

    @Test
    void unauthenticatedRequestDoesNotQueryRepositories() {
        assertThatThrownBy(() -> service.preview(ORDER_NUMBER, null))
                .isInstanceOf(UnauthenticatedException.class);
        verifyNoInteractions(orders, payments, refunds, deliveries, items);
    }

    @Test
    void missingAndOtherMembersOrdersHaveTheSameError() {
        when(orders.findByOrderNumberAndUser_UserIdAndUser_IsDeletedFalse(ORDER_NUMBER, MEMBER_ID))
                .thenReturn(Optional.of(order));
        assertThatThrownBy(() -> service.preview(ORDER_NUMBER, 99))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("주문을 찾을 수 없습니다.");
        assertThatThrownBy(() -> service.preview("unknown-order", MEMBER_ID))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("주문을 찾을 수 없습니다.");
        verifyNoInteractions(payments, refunds, deliveries, items);
    }

    @Test
    void missingPaymentDoesNotExposeAFakeAmount() {
        when(orders.findByOrderNumberAndUser_UserIdAndUser_IsDeletedFalse(ORDER_NUMBER, MEMBER_ID))
                .thenReturn(Optional.of(order));
        var preview = service.preview(ORDER_NUMBER, MEMBER_ID);
        assertThat(preview.status()).isEqualTo(Status.BLOCKED);
        assertThat(preview.paymentAmount()).isNull();
        assertThat(preview.localRemainingAmount()).isNull();
        assertThat(preview.paymentMethod()).isNull();
        assertThat(preview.items()).isEmpty();
        verifyNoInteractions(refunds, deliveries, items);
    }

    @Test
    void ordinaryPaidOrderPassesOnlyTheLocalPrecheck() {
        setUpPaidOrder();
        var item = new OrderItem();
        var product = new Product();
        product.setThumbnailUrl("https://example.com/thumbnail.png");
        item.setProduct(product);
        item.setProductNameSnapshot("Keyboard");
        item.setOrderedPrice(5_000);
        item.setQuantity(2);
        when(items.findByOrder(order)).thenReturn(List.of(item));
        when(refunds.findByPayment_PaymentIdOrderByRequestedAtDesc(21))
                .thenReturn(List.of(refund(RefundStatusType.REJECTED, 9_000)));

        var preview = service.preview(ORDER_NUMBER, MEMBER_ID);
        assertThat(preview.status()).isEqualTo(Status.PRECHECK_PASSED);
        assertThat(preview.localRemainingAmount()).isEqualTo(10_000);
        assertThat(preview.cancellationEnabled()).isFalse();
        assertThat(preview.pgProvider()).isEqualTo("TOSS");
        assertThat(preview.paymentMethod()).isEqualTo(PaymentMethodType.CARD);
        assertThat(preview.items()).containsExactly(new Item("Keyboard", 5_000, 2, product.getThumbnailUrl()));
        assertThat(preview.toString()).doesNotContain(payment.getPgTid());
    }

    @Test
    void pendingAndFullRefundsBlockWhilePartialRefundRequiresReview() {
        setUpPaidOrder();
        when(refunds.findByPayment_PaymentIdOrderByRequestedAtDesc(21))
                .thenReturn(List.of(refund(RefundStatusType.REQUESTED, 4_000)))
                .thenReturn(List.of(refund(RefundStatusType.COMPLETED, 10_000)))
                .thenReturn(List.of(refund(RefundStatusType.COMPLETED, 4_000)));

        var pending = service.preview(ORDER_NUMBER, MEMBER_ID);
        assertThat(pending.status()).isEqualTo(Status.BLOCKED);
        assertThat(pending.localRemainingAmount()).isEqualTo(10_000);
        var complete = service.preview(ORDER_NUMBER, MEMBER_ID);
        assertThat(complete.status()).isEqualTo(Status.BLOCKED);
        assertThat(complete.localRemainingAmount()).isZero();
        var partial = service.preview(ORDER_NUMBER, MEMBER_ID);
        assertThat(partial.status()).isEqualTo(Status.POLICY_REVIEW_REQUIRED);
        assertThat(partial.localRemainingAmount()).isEqualTo(6_000);
    }

    @Test
    void invalidKeyUnpaidStatusAndExcessRefundAreBlocked() {
        setUpPaidOrder();
        payment.setPgTid("key with a space");
        assertThat(service.preview(ORDER_NUMBER, MEMBER_ID).status()).isEqualTo(Status.BLOCKED);
        payment.setPgTid("key-for-local-test");
        payment.setPaymentStatus(PaymentStatusType.READY);
        assertThat(service.preview(ORDER_NUMBER, MEMBER_ID).status()).isEqualTo(Status.BLOCKED);
        payment.setPaymentStatus(PaymentStatusType.PAID);
        when(refunds.findByPayment_PaymentIdOrderByRequestedAtDesc(21))
                .thenReturn(List.of(refund(RefundStatusType.COMPLETED, 10_001)));
        var excess = service.preview(ORDER_NUMBER, MEMBER_ID);
        assertThat(excess.status()).isEqualTo(Status.BLOCKED);
        assertThat(excess.localRemainingAmount()).isNull();
    }

    @Test
    void shippingBuildsAndVirtualAccountsRequirePolicyReview() {
        setUpPaidOrder();
        payment.setPaymentMethod(PaymentMethodType.VIRTUAL_ACCOUNT);
        assertThat(service.preview(ORDER_NUMBER, MEMBER_ID).status()).isEqualTo(Status.POLICY_REVIEW_REQUIRED);

        payment.setPaymentMethod(PaymentMethodType.CARD);
        when(deliveries.existsByOrder_OrderIdAndDeliveryStatusNot(11, PREPARING)).thenReturn(true, false);
        assertThat(service.preview(ORDER_NUMBER, MEMBER_ID).status()).isEqualTo(Status.POLICY_REVIEW_REQUIRED);

        var item = new OrderItem();
        item.setManualBuild(new ManualBuild());
        when(items.findByOrder(order)).thenReturn(List.of(item));
        assertThat(service.preview(ORDER_NUMBER, MEMBER_ID).status()).isEqualTo(Status.POLICY_REVIEW_REQUIRED);
    }

    @Test
    void springDataRecognizesTheNewReadOnlyQueryPaths() {
        assertThat(new PartTree("findByOrderNumberAndUser_UserIdAndUser_IsDeletedFalse", Order.class).getParts())
                .hasSize(3);
        assertThat(new PartTree("findByOrder_OrderId", Payment.class).getParts()).hasSize(1);
        assertThat(new PartTree("existsByOrder_OrderIdAndDeliveryStatusNot", OrderDelivery.class).isExistsProjection()).isTrue();
    }

    private void setUpPaidOrder() {
        when(orders.findByOrderNumberAndUser_UserIdAndUser_IsDeletedFalse(ORDER_NUMBER, MEMBER_ID))
                .thenReturn(Optional.of(order));
        when(payments.findByOrder_OrderId(11)).thenReturn(Optional.of(payment));
    }

    private static Refund refund(RefundStatusType status, int amount) {
        var refund = new Refund();
        refund.setStatus(status);
        refund.setAmount(amount);
        return refund;
    }
}
