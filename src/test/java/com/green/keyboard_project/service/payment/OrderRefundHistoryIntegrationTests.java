package com.green.keyboard_project.service.payment;

import com.green.keyboard_project.entity.Order;
import com.green.keyboard_project.entity.OrderDelivery;
import com.green.keyboard_project.entity.OrderItem;
import com.green.keyboard_project.entity.Payment;
import com.green.keyboard_project.entity.Refund;
import com.green.keyboard_project.entity.User;
import com.green.keyboard_project.exceptions.PaymentConfirmationException;
import com.green.keyboard_project.repository.RefundRepository;
import com.green.keyboard_project.repository.UserRepository;
import com.green.keyboard_project.repository.orders.OrderRepository;
import com.green.keyboard_project.repository.orders.OrderDeliveryRepository;
import com.green.keyboard_project.repository.orders.OrderItemRepository;
import com.green.keyboard_project.repository.payment.PaymentRepository;
import com.green.keyboard_project.security.AuthCookieFactory;
import com.green.keyboard_project.security.JwtTokenProvider;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실서버가 아닌 전용 H2와 가상 취소 응답으로 DB 저장, 인증, API 응답을 함께 검증합니다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:refund_history_test;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE",
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=create-drop", "toss.payments.test.enabled=false"
})
class OrderRefundHistoryIntegrationTests {
    private static final OffsetDateTime CANCELED_AT = OffsetDateTime.parse("2026-11-20T09:00:00+09:00");
    private static final LocalDateTime NOW = CANCELED_AT.toLocalDateTime();
    private static final String HISTORY = "/api/payments/orders/{number}/refund/history";

    @Autowired WebApplicationContext context;
    @Autowired JwtTokenProvider jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired OrderRepository orders;
    @Autowired OrderDeliveryRepository deliveries;
    @Autowired OrderItemRepository items;
    @Autowired PaymentRepository payments;
    @Autowired RefundRepository refunds;
    @Autowired OrderCancellationStore store;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 테스트 픽스처를 저장하기 전에 전용 H2 이름을 확인합니다.
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualToIgnoringCase("refund_history_test");
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
    }

    @Test
    void ownerSeesSeparatePartialRowsFinalCancellationAndSafeResponse() throws Exception {
        Fixture fixture = payment();
        int first = reserve(fixture.payment(), 200, "첫 번째 부분 취소");
        store.complete(fixture.payment().getPaymentId(), first, cancellation("mock-first-" + first, 200));
        int second = reserve(fixture.payment(), 300, "두 번째 부분 취소");
        store.complete(fixture.payment().getPaymentId(), second, cancellation("mock-second-" + second, 300));
        assertThat(currentPayment(fixture).getPaymentStatus()).isEqualTo(Payment.PaymentStatusType.PAID);
        assertThat(currentPayment(fixture).getCancelledAt()).isNull();

        mockMvc.perform(get(HISTORY, fixture.orderNumber()).cookie(cookie(fixture.owner())))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.status").value("PARTIAL"))
                .andExpect(jsonPath("$.data.completedAmount").value(500))
                .andExpect(jsonPath("$.data.remainingAmount").value(500))
                .andExpect(jsonPath("$.data.entries.length()").value(2))
                .andExpect(jsonPath("$.data.entries[0].effect").value("PARTIAL"))
                .andExpect(jsonPath("$.data.entries[0].reason").value("두 번째 부분 취소"))
                .andExpect(jsonPath("$.data.entries[0].transactionKey").doesNotExist())
                .andExpect(jsonPath("$.data.paymentKey").doesNotExist())
                .andExpect(jsonPath("$.data.refundReceiveAccount").doesNotExist());

        int last = reserve(fixture.payment(), 500, "잔액 취소");
        var verified = cancellation("mock-final-" + last, 500);
        store.complete(fixture.payment().getPaymentId(), last, verified);
        store.complete(fixture.payment().getPaymentId(), last, verified);
        assertThat(currentPayment(fixture).getPaymentStatus()).isEqualTo(Payment.PaymentStatusType.CANCELLED);
        assertThat(currentPayment(fixture).getCancelledAt()).isEqualTo(NOW);
        assertThat(orders.findById(fixture.payment().getOrder().getOrderId()).orElseThrow().getOrderStatus())
                .isEqualTo(Order.OrderStatusType.ORDERED);

        mockMvc.perform(get(HISTORY, fixture.orderNumber()).cookie(cookie(fixture.owner())))
                .andExpect(jsonPath("$.data.status").value("FULL"))
                .andExpect(jsonPath("$.data.remainingAmount").value(0))
                .andExpect(jsonPath("$.data.entries.length()").value(3))
                .andExpect(jsonPath("$.data.entries[0].effect").value("FULL"));
    }

    @Test
    void anonymousAndOtherMembersCannotReadHistoryAndPendingDoesNotDeductBalance() throws Exception {
        Fixture fixture = payment();
        reserve(fixture.payment(), 200, "처리 대기");
        var rejected = refunds.findById(reserve(fixture.payment(), 100, "취소 거절")).orElseThrow();
        rejected.setStatus(Refund.RefundStatusType.REJECTED);
        refunds.saveAndFlush(rejected);

        mockMvc.perform(get(HISTORY, fixture.orderNumber())).andExpect(status().isUnauthorized());
        var other = cookie(member());
        String denied = mockMvc.perform(get(HISTORY, fixture.orderNumber()).cookie(other))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        mockMvc.perform(get(HISTORY, "ORD-MISSING").cookie(other))
                .andExpect(status().isNotFound()).andExpect(content().string(denied));
        mockMvc.perform(get(HISTORY, fixture.orderNumber()).cookie(cookie(fixture.owner())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.completedAmount").value(0))
                .andExpect(jsonPath("$.data.remainingAmount").value(1000))
                .andExpect(jsonPath("$.data.entries[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.data.entries[1].status").value("REQUESTED"));
    }

    @Test
    void sameTransactionKeyCannotCompleteTwoDifferentRefunds() {
        Fixture fixture = payment();
        int first = reserve(fixture.payment(), 100, "첫 취소");
        int second = reserve(fixture.payment(), 100, "다른 취소");
        var verified = cancellation("shared-mock-transaction-" + first, 100);
        store.complete(fixture.payment().getPaymentId(), first, verified);
        assertThatThrownBy(() -> store.complete(fixture.payment().getPaymentId(), second, verified))
                .isInstanceOfSatisfying(PaymentConfirmationException.class,
                        error -> assertThat(error.code()).isEqualTo("REFUND_TRANSACTION_DUPLICATE"));
        assertThat(refunds.findById(second).orElseThrow().getStatus()).isEqualTo(Refund.RefundStatusType.REQUESTED);
    }

    @Test
    void myOrderListAndDetailReturnOwnedItemsAndStoredDeliveryStatus() throws Exception {
        Fixture fixture = payment();
        var order = fixture.payment().getOrder();
        for (int quantity : new int[] {1, 2}) {
            var item = new OrderItem();
            item.setOrder(order);
            item.setProductNameSnapshot("Switch " + quantity);
            item.setOrderedPrice(250);
            item.setQuantity(quantity);
            items.saveAndFlush(item);
        }

        var delivery = new OrderDelivery();
        delivery.setOrder(order);
        delivery.setDeliveryStatus(OrderDelivery.DeliveryStatusType.DELIVERED);
        delivery.setRecipientName("테스트 회원");
        delivery.setRecipientPhone("01000000000");
        delivery.setPostalCode("00000");
        delivery.setRoadAddress("테스트 주소");
        delivery.setDetailAddress("테스트 상세");
        deliveries.saveAndFlush(delivery);

        String detail = "/api/orders/me/{orderNumber}";
        var owner = cookie(fixture.owner());
        mockMvc.perform(get("/api/orders/me").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].items.length()").value(2))
                .andExpect(jsonPath("$.data[0].deliveryStatus").value("DELIVERED"));
        mockMvc.perform(get(detail, fixture.orderNumber()).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.items[0].productName").value("Switch 1"))
                .andExpect(jsonPath("$.data.items[0].unitPrice").value(250))
                .andExpect(jsonPath("$.data.items[1].quantity").value(2))
                .andExpect(jsonPath("$.data.deliveryStatus").value("DELIVERED"))
                .andExpect(jsonPath("$.data.ordererEmail").doesNotExist());
        mockMvc.perform(get(detail, fixture.orderNumber())).andExpect(status().isUnauthorized());
        var other = cookie(member());
        String denied = mockMvc.perform(get(detail, fixture.orderNumber()).cookie(other))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        mockMvc.perform(get(detail, "ORD-MISSING").cookie(other))
                .andExpect(status().isNotFound()).andExpect(content().string(denied));
    }

    @Test
    void myOrdersKeepItemsWhenPaymentAndDeliveryAreMissing() throws Exception {
        Fixture fixture = payment();
        var item = new OrderItem();
        item.setOrder(fixture.payment().getOrder());
        item.setProductNameSnapshot("Unpaid item");
        item.setOrderedPrice(500);
        item.setQuantity(1);
        items.saveAndFlush(item);
        payments.deleteById(fixture.payment().getPaymentId());

        mockMvc.perform(get("/api/orders/me").cookie(cookie(fixture.owner())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].items[0].productName").value("Unpaid item"))
                .andExpect(jsonPath("$.data[0].deliveryStatus").doesNotExist())
                .andExpect(jsonPath("$.data[0].paymentStatus").doesNotExist());
    }

    private Fixture payment() {
        User owner = member();
        var order = new Order();
        order.setOrderNumber("ORD-" + UUID.randomUUID().toString().substring(0, 12));
        order.setUser(owner);
        order.setOrdererName(owner.getName());
        order.setOrdererPhone("01000000000");
        order.setOrdererEmail(owner.getEmail());
        order.setOrderStatus(Order.OrderStatusType.ORDERED);
        order.setTotalProductAmount(1000);
        order.setFinalPayAmount(1000);
        order.setCreatedAt(NOW);
        order = orders.saveAndFlush(order);

        var payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(Payment.PaymentMethodType.CARD);
        payment.setPgProvider("TOSS");
        payment.setPgTid("fixture-key-" + order.getOrderNumber());
        payment.setPaymentStatus(Payment.PaymentStatusType.PAID);
        payment.setPaidAmount(1000);
        return new Fixture(owner, order.getOrderNumber(), payments.saveAndFlush(payment));
    }

    private User member() {
        var user = new User();
        user.setEmail("mock-" + UUID.randomUUID().toString().substring(0, 10) + "@example.test");
        user.setPassword("mock-only-login-unused");
        user.setName("환불 모의 회원");
        user.setRole(User.RoleType.USER);
        user.setCreatedAt(NOW);
        user.setUpdatedAt(NOW);
        return users.saveAndFlush(user);
    }

    private Cookie cookie(User user) {
        return new Cookie(AuthCookieFactory.ACCESS_COOKIE, jwt.createAccessToken(user.getUserId(), user.getRole()));
    }

    private int reserve(Payment payment, int amount, String reason) {
        var row = new Refund();
        row.setPayment(payment);
        row.setAmount(amount);
        row.setReason(reason);
        row.setRequestedAt(NOW);
        row.setStatus(Refund.RefundStatusType.REQUESTED);
        return refunds.saveAndFlush(row).getRefundId();
    }

    private Payment currentPayment(Fixture fixture) {
        return payments.findById(fixture.payment().getPaymentId()).orElseThrow();
    }

    private static TossCancellationGateway.Cancellation cancellation(String key, int amount) {
        return new TossCancellationGateway.Cancellation(key, "mock", amount, CANCELED_AT, "DONE");
    }

    private record Fixture(User owner, String orderNumber, Payment payment) {}
}
