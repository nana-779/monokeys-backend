package com.green.keyboard_project.controller;

import com.green.keyboard_project.dto.ApiResponse;
import com.green.keyboard_project.dto.cart.CartAddRequest;
import com.green.keyboard_project.dto.cart.CartItemResponse;
import com.green.keyboard_project.dto.cart.CartQuantityUpdateRequest;
import com.green.keyboard_project.exceptions.InvalidStateException;
import com.green.keyboard_project.exceptions.ResourceNotFoundException;
import com.green.keyboard_project.exceptions.UnauthenticatedException;
import com.green.keyboard_project.service.CartService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {
    private final CartService cartService;

    /**
     * 1. 내 장바구니 목록 조회 (GET /api/cart)
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<CartItemResponse>>> getMyCart(@AuthenticationPrincipal Integer userId) {
        if (userId == null) {
            throw new UnauthenticatedException("로그인이 필요합니다.");
        }
        List<CartItemResponse> items = cartService.getCartItems(userId);
        return ResponseEntity.ok(ApiResponse.ok(items));
    }

    /**
     * 2. 장바구니 상품 추가 (POST /api/cart)
     */
    @PostMapping
    public ResponseEntity<ApiResponse<String>> addCartItem(
            @AuthenticationPrincipal Integer userId,
            @RequestBody CartAddRequest request
    ) {
        if (userId == null) {
            throw new UnauthenticatedException("로그인이 필요합니다.");
        }

        // 1. 전달된 ID 개수 카운트
        int idCount = 0;
        if (request.getProductId() != null) idCount++;
        if (request.getManualBuildId() != null) idCount++;
        if (request.getAutoBuildId() != null) idCount++;
        // 아무것도 안 보낸 경우 차단
        if (idCount == 0) {
            throw new ResourceNotFoundException("추가할 상품 정보가 없습니다.");
        }

        // 2개 이상의 id를 받았을 경우 차단
        if (idCount > 1) {
            throw new InvalidStateException("일반 상품, 수동 빌드, 자동 추천 빌드 중 정확히 1종류만 지정해야 합니다.");
        }

        if (request.getProductId() == null && request.getManualBuildId() == null && request.getAutoBuildId() == null) {
            throw new ResourceNotFoundException("추가할 상품 정보가 없습니다.");
        }

        if (request.getQuantity() == null) {
            throw new InvalidStateException("유효하지 않은 상품 갯수 입니다.");
        }

        // DTO 객체 전달
        cartService.addCartItem(userId, request);
        return ResponseEntity.ok(ApiResponse.ok("장바구니에 추가되었습니다."));
    }

    /**
     * 3. 장바구니 수량 변경 (PATCH /api/cart/{targetId})
     */
    @PatchMapping("/{targetId}")
    public ResponseEntity<ApiResponse<String>> updateCartItemQuantity(
            @AuthenticationPrincipal Integer userId,
            @PathVariable int targetId,
            @RequestBody CartQuantityUpdateRequest request
    ) {
        if (userId == null) {
            throw new UnauthenticatedException("로그인이 필요합니다.");
        }
        if (request.getQuantity() == null) {
            throw new InvalidStateException("유효하지 않은 상품 갯수 입니다.");
        }
        cartService.updateCartItemQuantity(userId, targetId, request.getQuantity());
        return ResponseEntity.ok(ApiResponse.ok("수량이 변경되었습니다."));
    }

    /**
     * 4. 장바구니 상품 단건 삭제 (DELETE /api/cart/{targetId})
     */
    @DeleteMapping("/{targetId}")
    public ResponseEntity<ApiResponse<String>> removeCartItem(
            @AuthenticationPrincipal Integer userId,
            @PathVariable int targetId
    ) {
        if (userId == null) {
            throw new UnauthenticatedException("로그인이 필요합니다.");
        }
        cartService.removeCartItem(userId, targetId);
        return ResponseEntity.ok(ApiResponse.ok("장바구니에서 삭제되었습니다."));
    }

    /**
     * 5. 장바구니 전체 비우기 (DELETE /api/cart)
     */
    @DeleteMapping
    public ResponseEntity<ApiResponse<String>> clearCart(
            @AuthenticationPrincipal Integer userId
    ) {
        if (userId == null) {
            throw new UnauthenticatedException("로그인이 필요합니다.");
        }
        cartService.clearCart(userId);
        return ResponseEntity.ok(ApiResponse.ok("장바구니가 비워졌습니다."));
    }
}