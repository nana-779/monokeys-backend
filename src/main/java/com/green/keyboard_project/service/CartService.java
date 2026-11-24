package com.green.keyboard_project.service;

import com.green.keyboard_project.dto.cart.CartItemResponse;
import com.green.keyboard_project.entity.CartItem;
import com.green.keyboard_project.entity.Product;
import com.green.keyboard_project.entity.User;
import com.green.keyboard_project.exceptions.InvalidStateException;
import com.green.keyboard_project.exceptions.ResourceNotFoundException;
import com.green.keyboard_project.repository.ProductRepository;
import com.green.keyboard_project.repository.UserRepository;
import com.green.keyboard_project.repository.cart.CartItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {
    private final CartItemRepository cartItemRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;

    /**
     * 회원의 장바구니 목록 조회
     */
    @Transactional(readOnly = true)
    public List<CartItemResponse> getCartItems(int userId) {
        List<CartItem> items = cartItemRepository.findByUser_UserId(userId);
        return items.stream().map(item -> {
            Product product = item.getProduct();
            return CartItemResponse.builder()
                    .cartItemId(item.getCartItemId())
                    .userId(userId)
                    .productId(product != null ? product.getProductId() : 0)
                    .name(product != null ? product.getName() : "")
                    .price(product != null ? product.getBasePrice() : 0)
                    .stockQuantity(product != null ? product.getStockQuantity() : 0)
                    .quantity(item.getQuantity())
                    .thumbnailUrl(product != null ? product.getThumbnailUrl() : "")
                    .customDesignData(item.getCustomDesignData())
                    .build();
        }).toList();
    }

    /**
     * 장바구니 상품 추가 (일반 상품)
     */
    @Transactional
    public void addCartItem(int userId, int productId, int quantity) {
        addCartItem(userId, productId, quantity, null);
    }

    /**
     * 장바구니 상품 추가 (일반 상품 + 커스텀 키캡 디자인 분리 지원)
     */
    @Transactional
    public void addCartItem(int userId, int productId, int quantity, String customDesignData) {
        User user = findUser(userId);
        Product product = findProduct(productId);
        validateOnSale(product);

        if (quantity <= 0 || quantity > product.getStockQuantity()) {
            throw new InvalidStateException("유효하지 않은 상품 갯수 입니다.");
        }

        String normalizedDesign =
                customDesignData != null && !customDesignData.isBlank() ? customDesignData.trim() : null;

        if (normalizedDesign != null) {
            int currentProductTotalQty = cartItemRepository.findAllByUserAndProduct(user, product)
                    .stream()
                    .mapToInt(CartItem::getQuantity)
                    .sum();
            if (currentProductTotalQty + quantity > product.getStockQuantity()) {
                throw new InvalidStateException("유효하지 않은 상품 갯수 입니다.");
            }
            CartItem customItem = new CartItem();
            customItem.setUser(user);
            customItem.setProduct(product);
            customItem.setCustomDesignData(normalizedDesign);
            customItem.setQuantity(quantity);
            customItem.setCreatedAt(LocalDateTime.now());
            customItem.setUpdatedAt(LocalDateTime.now());
            cartItemRepository.save(customItem);
            return;
        }

        Optional<CartItem> existingItem =
                cartItemRepository.findFirstByUserAndProductAndCustomDesignDataIsNull(user, product);

        if (existingItem.isPresent()) {
            CartItem item = existingItem.get();
            if (item.getQuantity() + quantity > product.getStockQuantity()) {
                throw new InvalidStateException("유효하지 않은 상품 갯수 입니다.");
            }
            item.setQuantity(item.getQuantity() + quantity);
            item.setUpdatedAt(LocalDateTime.now());
        } else {
            CartItem newItem = new CartItem();
            newItem.setUser(user);
            newItem.setProduct(product);
            newItem.setQuantity(quantity);
            newItem.setCreatedAt(LocalDateTime.now());
            newItem.setUpdatedAt(LocalDateTime.now());

            cartItemRepository.save(newItem);
        }
    }

    /**
     * 장바구니 상품 수량 변경 (PATCH /api/cart/{productId})
     */
    @Transactional
    public void updateCartItemQuantity(int userId, int productId, int quantity) {
        User user = findUser(userId);
        Product product = findProduct(productId);
        validateOnSale(product);

        if (quantity <= 0 || quantity > product.getStockQuantity()) {
            throw new InvalidStateException("유효하지 않은 상품 갯수 입니다.");
        }

        CartItem item = cartItemRepository.findFirstByUserAndProductAndCustomDesignDataIsNull(user, product)
                .or(() -> cartItemRepository.findFirstByUserAndProduct(user, product))
                .orElseThrow(() -> {
                    log.warn("장바구니에 존재하지 않는 상품 수량 변경 시도. userId={}, productId={}", userId, productId);
                    return new ResourceNotFoundException("장바구니에 해당 상품이 없습니다.");
                });

        item.setQuantity(quantity);
        item.setUpdatedAt(LocalDateTime.now());
    }

    /**
     * 장바구니 개별 행 수량 변경 (PATCH /api/cart/items/{cartItemId})
     */
    @Transactional
    public void updateCartItemQuantityById(int userId, int cartItemId, int quantity) {
        CartItem item = cartItemRepository.findByCartItemIdAndUser_UserId(cartItemId, userId)
                .orElseThrow(() -> {
                    log.warn("장바구니 개별 행 수량 변경 시도. userId={}, cartItemId={}", userId, cartItemId);
                    return new ResourceNotFoundException("장바구니에 해당 상품이 없습니다.");
                });

        Product product = item.getProduct();
        if (product != null) {
            validateOnSale(product);
            if (quantity <= 0 || quantity > product.getStockQuantity()) {
                throw new InvalidStateException("유효하지 않은 상품 갯수 입니다.");
            }
            int otherQty = cartItemRepository.findAllByUserAndProduct(item.getUser(), product)
                    .stream()
                    .filter(other -> other.getCartItemId() != cartItemId)
                    .mapToInt(CartItem::getQuantity)
                    .sum();
            if (otherQty + quantity > product.getStockQuantity()) {
                throw new InvalidStateException("유효하지 않은 상품 갯수 입니다.");
            }
        } else if (quantity <= 0) {
            throw new InvalidStateException("유효하지 않은 상품 갯수 입니다.");
        }

        item.setQuantity(quantity);
        item.setUpdatedAt(LocalDateTime.now());
    }

    /**
     * 장바구니 상품 단건 삭제 (DELETE /api/cart/{productId})
     */
    @Transactional
    public void removeCartItem(int userId, int productId) {
        User user = findUser(userId);
        Product product = findProduct(productId);
        cartItemRepository.findFirstByUserAndProductAndCustomDesignDataIsNull(user, product)
                .or(() -> cartItemRepository.findFirstByUserAndProduct(user, product))
                .ifPresent(cartItemRepository::delete);
    }

    /**
     * 장바구니 개별 행 단건 삭제 (DELETE /api/cart/items/{cartItemId})
     */
    @Transactional
    public void removeCartItemById(int userId, int cartItemId) {
        cartItemRepository.findByCartItemIdAndUser_UserId(cartItemId, userId)
                .ifPresent(cartItemRepository::delete);
    }

    /**
     * 장바구니 전체 비우기 (DELETE)
     */
    @Transactional
    public void clearCart(int userId) {
        User user = findUser(userId);
        cartItemRepository.deleteByUser(user);
    }

    private User findUser(int userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.warn("사용자를 찾을 수 없습니다. userId={}", userId);
                    return new ResourceNotFoundException("사용자를 찾을 수 없습니다.");
                });
    }

    /**
     * 판매중지되었거나 삭제된 상품은 장바구니에 담거나 수량을 바꿀 수 없음
     */
    private void validateOnSale(Product product) {
        if (!product.isActive() || product.isDeleted()) {
            log.warn("판매하지 않는 상품 장바구니 요청. productId={}", product.getProductId());
            throw new InvalidStateException("판매하지 않는 상품입니다.");
        }
    }

    private Product findProduct(int productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> {
                    log.warn("상품을 찾을 수 없습니다. productId={}", productId);
                    return new ResourceNotFoundException("상품을 찾을 수 없습니다.");
                });
    }
}
