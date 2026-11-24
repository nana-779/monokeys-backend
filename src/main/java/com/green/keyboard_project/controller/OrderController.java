package com.green.keyboard_project.controller;

import com.green.keyboard_project.dto.ApiResponse;
import com.green.keyboard_project.dto.order.MyOrderSummary;
import com.green.keyboard_project.dto.order.OrderCreateRequest;
import com.green.keyboard_project.dto.order.OrderResponse;
import com.green.keyboard_project.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders") // 주문 관련 공통 API 엔드포인트 접두사
@RequiredArgsConstructor
public class OrderController {
    private final OrderService orderService; // 주문 비즈니스 로직 주입

    /**
     * 1. 신규 주문 등록 (POST /api/orders)
     * - 클라이언트로부터 주문자 정보와 주문 상품 목록을 받아 주문 및 주문 품목을 저장합니다.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<OrderResponse>> createOrder(
            @AuthenticationPrincipal Integer userId,
            @Valid @RequestBody OrderCreateRequest request
    ) {
        OrderResponse response = orderService.createOrder(userId, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * 2. 주문 전체 목록 조회 (GET /api/orders)
     * - 생성된 모든 주문 목록을 최신 등록순으로 반환합니다.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<OrderResponse>>> getOrders() {
        List<OrderResponse> responses = orderService.getAllOrders();
        return ResponseEntity.ok(ApiResponse.ok(responses));
    }

    // 검증된 로그인 정보에서 회원 번호를 가져오며, 개인 주문 응답은 캐시에 저장하지 않습니다.
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<List<MyOrderSummary>>> getMyOrders(
            @AuthenticationPrincipal Integer userId
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(orderService.getMyOrders(userId)));
    }

    // 인증 주체의 회원 ID로 상세를 조회하고 개인 주문 응답은 캐시에 남기지 않습니다.
    @GetMapping("/me/{orderNumber}")
    public ResponseEntity<ApiResponse<MyOrderSummary>> getMyOrder(
            @PathVariable String orderNumber, @AuthenticationPrincipal Integer userId
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(orderService.getMyOrder(userId, orderNumber)));
    }

    /**
     * 3. 주문 단건 조회 - 주문 ID 기준 (GET /api/orders/{orderId})
     * - PK인 orderId로 특정 주문의 상세 정보와 품목 목록을 조회합니다.
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrderById(
            @PathVariable int orderId
    ) {
        OrderResponse response = orderService.getOrderById(orderId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * 4. 주문 단건 조회 - 주문번호 기준 (GET /api/orders/number/{orderNumber})
     * - 외부 노출용 고유 주문번호(ORD-...)로 특정 주문을 조회합니다.
     */
    @GetMapping("/number/{orderNumber}")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrderByOrderNumber(
            @PathVariable String orderNumber
    ) {
        OrderResponse response = orderService.getOrderByOrderNumber(orderNumber);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
