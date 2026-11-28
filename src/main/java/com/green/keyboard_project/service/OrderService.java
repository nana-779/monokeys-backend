package com.green.keyboard_project.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.green.keyboard_project.dto.cart.CartOrderLine;
import com.green.keyboard_project.dto.order.MyOrderSummary;
import com.green.keyboard_project.dto.order.MyOrderDetail;
import com.green.keyboard_project.dto.order.OrderCreateRequest;
import com.green.keyboard_project.dto.order.OrderItemRequest;
import com.green.keyboard_project.dto.order.OrderItemResponse;
import com.green.keyboard_project.dto.order.OrderResponse;
import com.green.keyboard_project.entity.*;
import com.green.keyboard_project.exceptions.InvalidStateException;
import com.green.keyboard_project.exceptions.ResourceNotFoundException;
import com.green.keyboard_project.exceptions.UnauthenticatedException;
import com.green.keyboard_project.repository.AutoBuildItemRepository;
import com.green.keyboard_project.repository.ManualBuildItemRepository;
import com.green.keyboard_project.repository.orders.OrderDeliveryRepository;
import com.green.keyboard_project.repository.payment.PaymentRepository;
import com.green.keyboard_project.repository.cart.CartItemRepository;
import com.green.keyboard_project.repository.orders.OrderItemRepository;
import com.green.keyboard_project.repository.orders.OrderRepository;
import com.green.keyboard_project.repository.ProductRepository;
import com.green.keyboard_project.repository.UserRepository;
import com.green.keyboard_project.service.payment.TossOrderConfirmClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Scheduled;

@Service
@RequiredArgsConstructor
public class OrderService {
    private final OrderRepository orderRepository;         // 주문(Order) 데이터 접근 계층
    private final OrderItemRepository orderItemRepository; // 주문 상품(OrderItem) 데이터 접근 계층
    private final UserRepository userRepository;           // 주문자 회원(User) 조회용
    private final CartItemRepository cartItemRepository;    // 장바구니(Cart) 조회용
    private final PaymentRepository paymentRepository;     // 결제(Payment) 데이터 접근 계층
    private final TossOrderConfirmClient tossOrderConfirmClient; // 토스페이먼츠 서버 최종 승인 클라이언트
    private final OrderDeliveryRepository orderDeliveryRepository; // order_delivery에 접근

    private final CartService cartService;

    private final ManualBuildItemRepository manualBuildItemRepository;
    private final AutoBuildItemRepository autoBuildItemRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 주문 생성 (주문 및 주문 상품 일괄 저장)
     * - @Transactional: 중간에 예외 발생 시 모든 DB 작업을 롤백하여 데이터 정합성을 보장합니다.
     */
    @Transactional
    public OrderResponse createOrder(Integer userId, OrderCreateRequest request) {
        // 주문은 반드시 로그인된 사용자만 가능하도록 수정
        if (userId == null) {
            throw new UnauthenticatedException("주문을 진행하려면 로그인이 필요합니다.");
        }

        User user = userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("등록된 회원 정보를 찾을 수 없습니다. (ID: " + userId + ")"));

        if (user.isDeleted()) {
            throw new InvalidStateException("탈퇴한 회원은 주문할 수 없습니다.");
        }

        // 주문 상품 목록 필수값 검증
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new InvalidStateException("주문 상품 목록이 비어 있습니다.");
        }

        // 주문자 이름 및 연락처(DB 11자리 제한) 정제
        String ordererName = request.getOrdererName() != null && !request.getOrdererName().isBlank()
                ? request.getOrdererName()
                : user.getName();

        String rawPhone = request.getOrdererPhone() != null && !request.getOrdererPhone().isBlank()
                ? request.getOrdererPhone()
                : user.getPhoneNumber();
        String cleanPhone = rawPhone != null ? rawPhone.replaceAll("[^0-9]", "") : "";
        if (cleanPhone.length() > 11) {
            cleanPhone = cleanPhone.substring(0, 11); // orders 테이블 orderer_phone 컬럼 길이 11자리 준수
        } else if (cleanPhone.isEmpty()) {
            cleanPhone = "01000000000";
        }

        String ordererEmail = user.getEmail();

        // [4] 고유 주문번호 생성 (형식: ORD-yyMMddHHmmss-UUID6자리, DB 컬럼 32자 준수)
        String timePart = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmss"));
        String uuidPart = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        String orderNumber = "ORD-" + timePart + "-" + uuidPart;

        // [5] 장바구니 기반 주문 상품 검증 및 엔티티 생성
        List<Integer> cartItemIds = request.getItems().stream()
                .map(OrderItemRequest::getCartItemId)
                .filter(Objects::nonNull)
                .toList();

        // cartItemId가 없는 요청은 즉시 거부
        if (cartItemIds.isEmpty()) {
            throw new InvalidStateException("주문할 장바구니 상품 정보가 올바르지 않습니다.");
        }

        List<OrderItem> itemsToSave = new ArrayList<>();
        int totalProductAmount = 0;

        // CartService의 고도화된 검증 활용 (단품 재고 + 키보드 빌드 부품 재고 일괄 검증)
        List<CartOrderLine> resolvedLines =
                cartService.resolveCartItemsForOrder(user.getUserId(), cartItemIds);
        for (CartOrderLine line : resolvedLines) {
            totalProductAmount += line.unitPrice() * line.quantity();
            OrderItem orderItem = new OrderItem();
            orderItem.setProduct(line.product());
            orderItem.setManualBuild(line.manualBuild());
            orderItem.setAutoBuild(line.autoBuild());
            orderItem.setCustomDesignData(line.customDesignData());
            orderItem.setProductNameSnapshot(line.itemNameSnapshot());
            orderItem.setOrderedPrice(line.unitPrice());
            orderItem.setQuantity(line.quantity());
            itemsToSave.add(orderItem);
        }

        int deliveryFee = 0; // 배송비 정책 (기본 0원)
        int finalPayAmount = totalProductAmount + deliveryFee;

        // [6] 주문(Order) 엔티티 저장
        Order order = new Order();
        order.setOrderNumber(orderNumber);
        order.setUser(user);
        order.setOrdererName(ordererName);
        order.setOrdererPhone(cleanPhone);
        order.setOrdererEmail(ordererEmail);
        // 회원 기본 주소가 나중에 바뀌어도 주문 당시 배송지를 확인할 수 있도록 주문에 보존합니다.
        order.setPostalCode(request.getPostalCode().strip());
        order.setRoadAddress(request.getRoadAddress().strip());
        order.setDetailAddress(request.getDetailAddress().strip());
        String deliveryMemo = request.getDeliveryMemo();
        order.setDeliveryMemo(deliveryMemo == null || deliveryMemo.isBlank() ? null : deliveryMemo.strip());
        order.setOrderStatus(Order.OrderStatusType.PENDING);
        order.setTotalProductAmount(totalProductAmount);
        order.setDeliveryFee(deliveryFee);
        order.setFinalPayAmount(finalPayAmount);
        order.setOrderedAt(null);
        order.setCreatedAt(LocalDateTime.now());

        Order savedOrder = orderRepository.save(order);

        // [7] 주문 상품(OrderItem) 목록에 생성된 주문(Order)을 연결하여 저장
        for (OrderItem orderItem : itemsToSave) {
            orderItem.setOrder(savedOrder);
            orderItemRepository.save(orderItem);
        }

        // [8] 저장 완료된 엔티티들을 응답 DTO로 변환하여 반환
        return toOrderResponse(savedOrder, itemsToSave);
    }

    /**
     * 전체 주문 목록 최신 등록순 조회
     */
    @Transactional(readOnly = true)
    public List<OrderResponse> getAllOrders() {
        List<Order> orders = orderRepository.findAllByOrderByCreatedAtDesc();
        if (orders.isEmpty()) {
            return  Collections.emptyList();
        }

        // 주문 ID 목록 추출
        List<Integer> orderIds = orders.stream().map(Order::getOrderId).toList();

        Map<Integer, List<OrderItem>> itemsByOrderId =
            orderItemRepository.findMyOrderItems(orderIds).stream()
                    .collect(Collectors.groupingBy(item ->
                            item.getOrder().getOrderId()));

        // 메모리에서 DTO 조립
        List<OrderResponse> responses = new ArrayList<>();
        for (Order order : orders) {
            List<OrderItem> items = itemsByOrderId.getOrDefault(order.getOrderId(),
                    Collections.emptyList());
            responses.add(toOrderResponse(order, items));
        }

        return responses;
    }

    @Transactional
    public void confirmMyDelivery(Integer userId, String orderNumber) {
        if (userId == null) throw new UnauthenticatedException("로그인이 필요합니다.");
        if (orderDeliveryRepository.confirmMyDelivery(orderNumber, userId) == 0) {
            throw new InvalidStateException("본인의 배송 중인 주문만 수령 확인할 수 있습니다.");
        }
    }

    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void completeOverdueDeliveries() {
        orderDeliveryRepository.completeOverdueDeliveries();
    }

    // 회원 번호가 없는 요청은 조회를 시작하기 전에 막습니다.
    @Transactional(readOnly = true)
    public List<MyOrderSummary> getMyOrders(Integer userId) {
        return readMyOrders(userId, null);
    }

    /** 타인 주문과 존재하지 않는 주문에 같은 오류를 반환합니다. */
    @Transactional(readOnly = true)
    public MyOrderDetail getMyOrder(Integer userId, String orderNumber) {
        var summary = readMyOrders(userId, orderNumber).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("주문을 찾을 수 없습니다."));
        var order = orderRepository.findByOrderNumberAndUser_UserIdAndUser_IsDeletedFalse(orderNumber, userId)
                .orElseThrow(() -> new ResourceNotFoundException("주문을 찾을 수 없습니다."));
        return new MyOrderDetail(summary, order.getOrdererName(), order.getOrdererPhone(),
                order.getPostalCode(), order.getRoadAddress(), order.getDetailAddress());
    }

    private List<MyOrderSummary> readMyOrders(Integer userId, String orderNumber) {
        if (userId == null) {
            throw new UnauthenticatedException("로그인이 필요합니다.");
        }
        var headers = orderRepository.findMyOrderSummaries(userId, orderNumber);
        if (headers.isEmpty()) return List.of();

        // 앞에서 소유권을 확인한 주문의 품목을 한 번에 읽고 주문 ID별로 묶습니다.
        var orderIds = headers.stream().map(MyOrderSummary.Header::orderId).toList();
        var itemsByOrder = new HashMap<Integer, List<MyOrderSummary.Item>>();
        for (var item : orderItemRepository.findMyOrderItems(orderIds)) {
            var product = item.getProduct();

            String thumbnailUrl = extractThumbnailUrl(item, product);

            var summary = new MyOrderSummary.Item(
                    item.getOrderItemId(),
                    item.getProductNameSnapshot(),
                    item.getOrderedPrice(),
                    item.getQuantity(),
                    thumbnailUrl
            );
            itemsByOrder.computeIfAbsent(item.getOrder().getOrderId(), id -> new ArrayList<>()).add(summary);
        }
        return headers.stream().map(header -> header.withItems(itemsByOrder.getOrDefault(header.orderId(), List.of())))
                .toList();
    }

    /**
     * 주문 단건 조회 (주문 ID 기준)
     */
    @Transactional(readOnly = true)
    public OrderResponse getOrderById(Integer userId, int orderId) {
        if (userId == null) {
            throw new UnauthenticatedException("주문을 조회하려면 로그인이 필요합니다.");
        }
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("주문을 찾을 수 없습니다. (ID: " + orderId + ")"));
        // 본인 주문이 아니면 조회 불가
        if (order.getUser() != null && !Objects.equals(order.getUser().getUserId(), userId)) {
            throw new InvalidStateException("본인의 주문만 조회할 수 있습니다.");
        }
        List<OrderItem> items = orderItemRepository.findByOrder(order);
        return toOrderResponse(order, items);
    }

    /**
     * 주문 단건 조회 (주문번호 기준)
     */
    @Transactional(readOnly = true)
    public OrderResponse getOrderByOrderNumber(Integer userId, String orderNumber) {
        if (userId == null) {
            throw new UnauthenticatedException("주문을 조회하려면 로그인이 필요합니다.");
        }
        Order order = orderRepository.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new ResourceNotFoundException("주문을 찾을 수 없습니다. (주문번호: " + orderNumber + ")"));
        // 본인 주문이 아니면 조회 불가
        if (order.getUser() != null && !Objects.equals(order.getUser().getUserId(), userId)) {
            throw new InvalidStateException("본인의 주문만 조회할 수 있습니다.");
        }
        List<OrderItem> items = orderItemRepository.findByOrder(order);
        return toOrderResponse(order, items);
    }

    /**
     * 토스페이먼츠 결제 승인 완료 처리 (실제 토스 서버 최종 승인 후 DB 반영)
     */
    @Transactional
    public OrderResponse completePayment(Integer userId, String orderNumber, String paymentKey, int amount) {
        Order order = orderRepository.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new ResourceNotFoundException("주문 정보를 찾을 수 없습니다."));

        if (order.getUser() != null) {
            if (userId == null) {
                throw new UnauthenticatedException("결제 승인을 진행하려면 로그인이 필요합니다.");
            }
            if (order.getUser().getUserId() != userId) {
                throw new InvalidStateException("본인의 주문만 결제를 진행할 수 있습니다.");
            }
        }

        if (order.getOrderStatus() == Order.OrderStatusType.ORDERED) {
            List<OrderItem> items = orderItemRepository.findByOrder(order);
            return toOrderResponse(order, items);
        }

        if (order.getOrderStatus() == Order.OrderStatusType.CANCELLED) {
            throw new InvalidStateException("이미 취소된 주문입니다.");
        }

        if (order.getFinalPayAmount() != amount) {
            throw new InvalidStateException("결제 금액이 주문 금액과 일치하지 않습니다. (요청 금액: " + amount + "원, 주문 금액: " + order.getFinalPayAmount() + "원)");
        }

        // 1. 토스페이먼츠 공식 서버 최종 승인 요청 (승인 실패 시 예외 발생 -> 롤백)
        Payment.PaymentMethodType paymentMethod = tossOrderConfirmClient.confirmPayment(
                paymentKey,
                orderNumber,
                order.getFinalPayAmount()
        );

        // 1-1. 결제 승인 완료 시점에 실제 재고 차감 반영
        List<OrderItem> items = orderItemRepository.findByOrder(order);

        try {
            for (OrderItem item : items) {
                if (item.getQuantity() <= 0) continue;

                if (item.getProduct() != null) {
                    // 단품 재고 차감
                    Product product = item.getProduct();
                    if (product.getStockQuantity() < item.getQuantity()) {
                        throw new InvalidStateException("상품 재고가 부족합니다. (" + product.getName() +")");
                    }
                    product.setStockQuantity(product.getStockQuantity() - item.getQuantity());
                } else if (item.getManualBuild() != null) {
                    int buildId = item.getManualBuild().getManualBuildId();
                    var parts = manualBuildItemRepository.findWithProductByManualBuildIds(List.of(buildId));
                    for (var partItem : parts) {
                        Product partProduct = partItem.getProduct();
                        if (partProduct != null) {
                            if (partProduct.getStockQuantity() < item.getQuantity()) {
                                throw new InvalidStateException("부품 재고가 부족하여 결제를 완료할 수 없습니다. (" + partProduct.getName() + ")");
                            }
                            partProduct.setStockQuantity(partProduct.getStockQuantity() - item.getQuantity());
                        }
                    }
                } else if (item.getAutoBuild() != null) {
                    int buildId = item.getAutoBuild().getAutoBuildId();
                    var parts = autoBuildItemRepository.findWithProductByAutoBuildIds(List.of(buildId));
                    for (var partItem : parts) {
                        Product partProduct = partItem.getProduct();
                        if (partProduct != null) {
                            if (partProduct.getStockQuantity() < item.getQuantity()) {
                                throw new InvalidStateException("부품 재고가 부족하여 결제를 완료할 수 없습니다. (" + partProduct.getName() + ")");
                            }
                            partProduct.setStockQuantity(partProduct.getStockQuantity() - item.getQuantity());
                        }
                    }
                }
            }

            // 2. 주문 상태를 결제 완료(ORDERED)로 변경 및 결제 승인 시각 기록
            order.setOrderStatus(Order.OrderStatusType.ORDERED);
            order.setOrderedAt(LocalDateTime.now());
            orderRepository.save(order);

            // 3. 결제(Payment) 이력 저장
            Payment payment = new Payment();
            payment.setOrder(order);
            payment.setPaymentMethod(paymentMethod);
            payment.setPgProvider("TOSS");
            payment.setPgTid(paymentKey);
            payment.setPaymentStatus(Payment.PaymentStatusType.PAID);
            payment.setPaidAmount(amount);
            payment.setPaidAt(LocalDateTime.now());
            paymentRepository.save(payment);

            // 결제가 완료된 주문의 배송 준비 행 생성
            OrderDelivery delivery = new OrderDelivery();
            delivery.setOrder(order);
            delivery.setDeliveryStatus(OrderDelivery.DeliveryStatusType.PREPARING);
            orderDeliveryRepository.save(delivery);

            // 4. 결제 성공 시 회원의 장바구니 비우기
            if (order.getUser() != null) {
                List<CartItem> userCartItems = cartItemRepository.findByUser_UserId(order.getUser().getUserId());
                for (OrderItem orderItem : items) {
                    for (CartItem cartItem : userCartItems) {
                        boolean matchProduct = false;
                        if (orderItem.getProduct() != null && cartItem.getProduct() != null) {
                            boolean sameId = cartItem.getProduct().getProductId() == orderItem.getProduct().getProductId();

                            boolean hasCustomDesign = (orderItem.getCustomDesignData() != null && !orderItem.getCustomDesignData().isBlank()) || (cartItem.getCustomDesignData() != null && !cartItem.getCustomDesignData().isBlank());

                            if (hasCustomDesign) {
                                matchProduct = sameId && Objects.equals(orderItem.getCustomDesignData(), cartItem.getCustomDesignData());
                            } else {
                                matchProduct = sameId; // 순수 일반 상품은 productId만 비교
                            }
                        }
                        boolean matchManual = orderItem.getManualBuild() != null
                                && cartItem.getManualBuild() != null
                                && cartItem.getManualBuild().getManualBuildId() == orderItem.getManualBuild().getManualBuildId();
                        boolean matchAuto = orderItem.getAutoBuild() != null
                                && cartItem.getAutoBuild() != null
                                && cartItem.getAutoBuild().getAutoBuildId() == orderItem.getAutoBuild().getAutoBuildId();
                        if (matchProduct || matchManual || matchAuto) {
                            cartItemRepository.delete(cartItem);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 어떤 이유에서든 실패한다면 결제 취소
            tossOrderConfirmClient.cancelPayment(paymentKey, "주문 후처리 실패(재고 부족 등)로 인한 자동 결제 취소");
            throw e;
        }

        return toOrderResponse(order, items);
    }

    /**
     * 결제 실패 또는 중간 취소 시 주문 상태를 CANCELLED(취소됨)로 변경
     */
    @Transactional
    public void failPayment(Integer userId, String orderNumber, String reason) {
        if (userId == null) {
            throw new UnauthenticatedException("결제 취소를 진행하려면 로그인이 필요합니다.");
        }
        orderRepository.findByOrderNumber(orderNumber).ifPresent(order -> {
            // 본인 주문이 아니면 취소 불가
            if (order.getUser() != null && !Objects.equals(order.getUser().getUserId(), userId)) {
                throw new InvalidStateException("본인의 주문만 취소 처리할 수 있습니다.");
            }
            if (order.getOrderStatus() == Order.OrderStatusType.PENDING) {
                order.setOrderStatus(Order.OrderStatusType.CANCELLED);
                orderRepository.save(order);
            }
        });
    }

    /**
     * 엔티티(Order, OrderItem) -> 클라이언트 응답 DTO(OrderResponse) 변환 헬퍼 메서드
     */
    private OrderResponse toOrderResponse(Order order, List<OrderItem> items) {
        List<OrderItemResponse> itemResponses = new ArrayList<>();
        if (items != null) {
            for (OrderItem item : items) {
                itemResponses.add(OrderItemResponse.builder()
                        .orderItemId(item.getOrderItemId())
                        .productId(item.getProduct() != null ? item.getProduct().getProductId() : null)
                        .manualBuildId(item.getManualBuild() != null ? item.getManualBuild().getManualBuildId() : null)
                        .autoBuildId(item.getAutoBuild() != null ? item.getAutoBuild().getAutoBuildId() : null)
                        .customDesignData(item.getCustomDesignData())
                        .productName(item.getProductNameSnapshot())
                        .unitPrice(item.getOrderedPrice())
                        .quantity(item.getQuantity())
                        .totalPrice(item.getOrderedPrice() * item.getQuantity())
                        .build());
            }
        }

        return OrderResponse.builder()
                .orderId(order.getOrderId())
                .orderNumber(order.getOrderNumber())
                .orderStatus(order.getOrderStatus().name())
                .ordererName(order.getOrdererName())
                .ordererPhone(order.getOrdererPhone())
                .ordererEmail(order.getOrdererEmail())
                .totalProductAmount(order.getTotalProductAmount())
                .deliveryFee(order.getDeliveryFee())
                .finalPayAmount(order.getFinalPayAmount())
                .orderedAt(order.getOrderedAt())
                .createdAt(order.getCreatedAt())
                .items(itemResponses)
                .build();
    }

    /**
     * 주문 품목에서 썸네일 이미지 URL 추출 (커스텀 키캡의 디자인 미리보기 이미지 우선)
     */
    private String extractThumbnailUrl(OrderItem item, Product product) {
        if (item.getCustomDesignData() != null && !item.getCustomDesignData().isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(item.getCustomDesignData());

                String previewUrl = node.path("previewImageUrl").asText(null);
                if (previewUrl == null || previewUrl.isBlank()) {
                    previewUrl = node.path("printImageUrl").asText(null);
                }
                if (previewUrl != null && !previewUrl.isBlank()) {
                    return previewUrl;
                }
            } catch (Exception ignored) {
            }
        }
        return product != null ? product.getThumbnailUrl() : null;
    }
}
