package com.green.keyboard_project.service.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.green.keyboard_project.entity.CartItem;
import com.green.keyboard_project.entity.Category;
import com.green.keyboard_project.entity.Order;
import com.green.keyboard_project.entity.OrderDelivery;
import com.green.keyboard_project.entity.OrderItem;
import com.green.keyboard_project.entity.Payment;
import com.green.keyboard_project.entity.Product;
import com.green.keyboard_project.entity.Refund;
import com.green.keyboard_project.entity.User;
import com.green.keyboard_project.exceptions.PaymentConfirmationException;
import com.green.keyboard_project.repository.CategoryRepository;
import com.green.keyboard_project.repository.ProductRepository;
import com.green.keyboard_project.repository.RefundRepository;
import com.green.keyboard_project.repository.UserRepository;
import com.green.keyboard_project.repository.cart.CartItemRepository;
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
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.hamcrest.Matchers.containsString;
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
    @Autowired CategoryRepository categories;
    @Autowired ProductRepository products;
    @Autowired CartItemRepository cartItems;
    @Autowired OrderRepository orders;
    @Autowired OrderDeliveryRepository deliveries;
    @Autowired OrderItemRepository items;
    @Autowired PaymentRepository payments;
    @Autowired RefundRepository refunds;
    @Autowired OrderCancellationStore store;
    @MockitoBean TossCancellationGateway gateway;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // 테스트 픽스처를 저장하기 전에 전용 H2 이름을 확인합니다.
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualToIgnoringCase("refund_history_test");
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
    }

    @Test
    void cancellationRequiresReviewAndRestocksOnceAfterConfirmation() throws Exception {
        Fixture fixture = payment();
        var product = workflowItem(fixture, OrderDelivery.DeliveryStatusType.PREPARING);
        String path = "/api/payments/orders/{number}/refund/request";
        String payload = """
                {"type":"CANCELLATION","reason":"단순 변심"}
                """;
        mockMvc.perform(post(path, fixture.orderNumber()).cookie(cookie(fixture.owner()))
                        .header("Origin", "http://localhost:3000").contentType("application/json").content(payload))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"));
        assertThat(currentPayment(fixture).getPaymentStatus()).isEqualTo(Payment.PaymentStatusType.PAID);
        assertThat(products.findById(product.getProductId()).orElseThrow().getStockQuantity()).isEqualTo(product.getStockQuantity());
        mockMvc.perform(get("/api/admin/refund-requests").cookie(cookie(fixture.owner())))
                .andExpect(status().isForbidden());
        var response = store.workflow(fixture.orderNumber(), fixture.owner().getUserId());
        assertThatThrownBy(() -> store.requestWorkflow(fixture.orderNumber(), fixture.owner().getUserId(),
                new OrderCancellationStore.WorkflowRequest(Refund.RequestType.CANCELLATION, "duplicate", null)))
                .isInstanceOf(PaymentConfirmationException.class);
        store.withdrawWorkflow(fixture.orderNumber(), fixture.owner().getUserId(), response.id());
        assertThatThrownBy(() -> store.inspectWorkflow(response.id(), false))
                .isInstanceOf(PaymentConfirmationException.class);

        var request = new OrderCancellationStore.WorkflowRequest(Refund.RequestType.CANCELLATION, "다시 요청", null);
        var rejected = store.requestWorkflow(fixture.orderNumber(), fixture.owner().getUserId(), request);
        var admin = admin();
        store.rejectWorkflow(rejected.id(), "반려 테스트");
        assertThat(store.workflow(fixture.orderNumber(), fixture.owner().getUserId()).rejectionReason()).isEqualTo("반려 테스트");
        mockMvc.perform(get("/api/admin/refund-requests").cookie(cookie(admin))).andExpect(status().isOk());

        var queued = store.requestWorkflow(fixture.orderNumber(), fixture.owner().getUserId(), request);
        var reviewPath = "/api/admin/refund-requests/{id}";
        String approve = """
                {"action":"APPROVE_CANCELLATION"}
                """;
        mockMvc.perform(post(reviewPath, queued.id()).cookie(cookie(fixture.owner()))
                        .header("Origin", "http://localhost:3000").contentType("application/json").content(approve))
                .andExpect(status().isForbidden());
        verifyNoInteractions(gateway);
        var confirmed = stubGateway(fixture, queued);
        mockMvc.perform(post(reviewPath, queued.id()).cookie(cookie(admin))
                        .header("Origin", "http://localhost:3000").contentType("application/json").content(approve))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLATION_CONFIRMED"));
        assertThatThrownBy(() -> store.withdrawWorkflow(fixture.orderNumber(), fixture.owner().getUserId(), queued.id()))
                .isInstanceOf(PaymentConfirmationException.class);
        store.complete(fixture.payment().getPaymentId(), queued.id(), confirmed);
        verify(gateway, times(1)).cancel(anyString(), anyString(), isNull(), isNull(), anyString());
        assertThat(currentPayment(fixture).getPaymentStatus()).isEqualTo(Payment.PaymentStatusType.CANCELLED);
        assertThat(products.findById(product.getProductId()).orElseThrow().getStockQuantity()).isEqualTo(product.getStockQuantity() + 1);
    }

    @Test
    void returnRequiresManualReceiptAndCollectionWithoutRestocking() throws Exception {
        Fixture fixture = payment();
        var product = workflowItem(fixture, OrderDelivery.DeliveryStatusType.DELIVERED);
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        int memberId = fixture.owner().getUserId();
        var request = new OrderCancellationStore.WorkflowRequest(Refund.RequestType.RETURN, "오배송",
                Refund.ReturnReasonType.WRONG_ITEM);
        assertThatThrownBy(() -> store.requestWorkflow(fixture.orderNumber(), memberId, request))
                .isInstanceOf(PaymentConfirmationException.class);
        mockMvc.perform(post("/api/orders/me/{orderNumber}/receipt", fixture.orderNumber())
                        .cookie(cookie(fixture.owner())).header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk());
        for (Refund.ReturnReasonType reason : Refund.ReturnReasonType.values()) {
            int days = reason == Refund.ReturnReasonType.CHANGE_OF_MIND ? 7 : 30;
            var boundary = new OrderCancellationStore.WorkflowRequest(Refund.RequestType.RETURN, "기한 검증", reason);
            for (int age : new int[]{days + 1, days}) {
                jdbc.update("UPDATE order_delivery SET receipt_confirmed_at=? WHERE order_id=?",
                        today.minusDays(age).atStartOfDay(), fixture.payment().getOrder().getOrderId());
                if (age > days) assertThatThrownBy(() -> store.requestWorkflow(fixture.orderNumber(), memberId, boundary))
                        .isInstanceOf(PaymentConfirmationException.class);
                else store.withdrawWorkflow(fixture.orderNumber(), memberId,
                        store.requestWorkflow(fixture.orderNumber(), memberId, boundary).id());
            }
        }
        var queued = store.requestWorkflow(fixture.orderNumber(), memberId, request);
        var admin = admin();
        assertThat(store.inspectWorkflow(queued.id(), true).refundId()).isEqualTo(queued.id());
        store.holdReturn(queued.id());
        assertThat(store.workflow(fixture.orderNumber(), memberId).status()).isEqualTo(Refund.RefundStatusType.AWAITING_RETURN);
        verifyNoInteractions(gateway);
        stubGateway(fixture, queued);
        mockMvc.perform(post("/api/admin/refund-requests/{id}", queued.id()).cookie(cookie(admin))
                        .header("Origin", "http://localhost:3000").contentType("application/json")
                        .content("{\"action\":\"RECEIVE_RETURN\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLATION_CONFIRMED"));
        assertThat(products.findById(product.getProductId()).orElseThrow().getStockQuantity()).isEqualTo(product.getStockQuantity());
        assertThat(deliveries.findByOrder_OrderId(fixture.payment().getOrder().getOrderId()).orElseThrow().getDeliveryStatus())
                .isEqualTo(OrderDelivery.DeliveryStatusType.RETURNED);
    }

    private TossCancellationGateway.Cancellation stubGateway(Fixture fixture, OrderCancellationStore.WorkflowView request) {
        String key = fixture.payment().getPgTid();
        String reason = request.reason() + " [MK-R" + request.id() + "]";
        var confirmed = new TossCancellationGateway.Cancellation("workflow-" + request.id(), reason, 1000, CANCELED_AT, "DONE");
        when(gateway.lookup(key)).thenReturn(new TossCancellationGateway.Snapshot(key, fixture.orderNumber(),
                "KRW", "DONE", 1000, 1000, true, false, List.of()));
        when(gateway.cancel(eq(key), eq(reason), isNull(), isNull(), anyString())).thenReturn(
                new TossCancellationGateway.Snapshot(key, fixture.orderNumber(), "KRW", "CANCELED",
                        1000, 0, true, false, List.of(confirmed)));
        return confirmed;
    }

    private Product workflowItem(Fixture fixture, OrderDelivery.DeliveryStatusType status) {
        var order = fixture.payment().getOrder();
        var delivery = new OrderDelivery();
        delivery.setOrder(order);
        delivery.setDeliveryStatus(status);
        deliveries.saveAndFlush(delivery);
        var product = cartProduct(fixture.owner());
        var item = new OrderItem();
        item.setOrder(order);
        item.setProduct(product);
        item.setProductNameSnapshot(product.getName());
        item.setOrderedPrice(1000);
        item.setQuantity(1);
        items.saveAndFlush(item);
        return product;
    }

    private User admin() {
        var admin = member();
        admin.setRole(User.RoleType.ADMIN);
        return users.saveAndFlush(admin);
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
        assertThat(orders.findById(fixture.payment().getOrder().getOrderId()).orElseThrow().getOrderStatus())
                .isEqualTo(Order.OrderStatusType.ORDERED);

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
                .isEqualTo(Order.OrderStatusType.CANCELLED);

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

    @Test
    void orderCreationStoresTrimmedCheckoutAddress() throws Exception {
        // 체크아웃과 같은 JSON으로 요청해 앞뒤 공백을 뺀 주소 세 값이 주문 행에 저장되는지 확인합니다.
        var owner = member();
        var product = cartProduct(owner);
        mockMvc.perform(post("/api/orders").cookie(cookie(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderRequest(owner, product))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.finalPayAmount").value(500));

        assertThat(jdbc.queryForMap(
                "select postal_code, road_address, detail_address from orders where user_id = ?", owner.getUserId()))
                .containsEntry("POSTAL_CODE", "04524")
                .containsEntry("ROAD_ADDRESS", "서울 세종대로")
                .containsEntry("DETAIL_ADDRESS", "101호");
        assertThat(products.findById(product.getProductId()).orElseThrow().getStockQuantity()).isEqualTo(4);
    }

    @Test
    void invalidCheckoutAddressesDoNotSaveOrdersOrChangeStock() throws Exception {
        // 주소별 누락·공백·길이 초과 요청이 저장과 재고 차감 전에 거부되는지 확인합니다.
        var owner = member();
        var product = cartProduct(owner);
        var lengths = Map.of("postalCode", 10, "roadAddress", 255, "detailAddress", 255);
        for (var field : lengths.entrySet()) {
            var missing = orderRequest(owner, product);
            missing.remove(field.getKey());
            var blank = orderRequest(owner, product);
            blank.put(field.getKey(), "   ");
            var tooLong = orderRequest(owner, product);
            tooLong.put(field.getKey(), "가".repeat(field.getValue() + 1));
            for (var request : List.of(missing, blank, tooLong)) {
                mockMvc.perform(post("/api/orders").cookie(cookie(owner))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.message", containsString(field.getKey())));
            }
        }
        assertThat(jdbc.queryForObject("select count(*) from orders where user_id = ?", Integer.class, owner.getUserId()))
                .isZero();
        assertThat(products.findById(product.getProductId()).orElseThrow().getStockQuantity()).isEqualTo(5);
    }

    private Map<String, Object> orderRequest(User owner, Product product) {
        return new HashMap<>(Map.of("ordererEmail", owner.getEmail(), "postalCode", " 04524 ",
                "roadAddress", " 서울 세종대로 ", "detailAddress", " 101호 ",
                "items", List.of(Map.of("productId", product.getProductId(), "quantity", 1))));
    }

    private Product cartProduct(User owner) {
        var category = new Category();
        category.setCategoryId("address-test-" + UUID.randomUUID());
        category.setName("테스트 상품 분류");
        var product = new Product();
        product.setCategory(categories.saveAndFlush(category));
        product.setName("배송지 테스트 상품");
        product.setBasePrice(500);
        product.setStockQuantity(5);
        product.setActive(true);
        product.setCreatedAt(NOW);
        product.setUpdatedAt(NOW);
        product = products.saveAndFlush(product);
        var cartItem = new CartItem();
        cartItem.setUser(owner);
        cartItem.setProduct(product);
        cartItem.setQuantity(1);
        cartItem.setCreatedAt(NOW);
        cartItem.setUpdatedAt(NOW);
        cartItems.saveAndFlush(cartItem);
        return product;
    }

    private Fixture payment() {
        User owner = member();
        var order = new Order();
        order.setOrderNumber("ORD-" + UUID.randomUUID().toString().substring(0, 12));
        order.setUser(owner);
        order.setOrdererName(owner.getName());
        order.setOrdererPhone("01000000000");
        order.setOrdererEmail(owner.getEmail());
        // 배송 정보 행과 별개로 주문 픽스처에 주문 당시 주소를 저장합니다.
        order.setPostalCode("00000");
        order.setRoadAddress("테스트 주소");
        order.setDetailAddress("테스트 상세");
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
