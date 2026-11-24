package com.green.keyboard_project.service;

import com.green.keyboard_project.dto.cart.CartAddRequest;
import com.green.keyboard_project.dto.cart.CartItemResponse;
import com.green.keyboard_project.entity.*;
import com.green.keyboard_project.exceptions.InvalidStateException;
import com.green.keyboard_project.exceptions.ResourceNotFoundException;
import com.green.keyboard_project.repository.*;
import com.green.keyboard_project.repository.cart.CartItemRepository;
import com.green.keyboard_project.repository.orders.OrderItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {
    private final CartItemRepository cartItemRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final ManualBuildRepository manualBuildRepository;
    private final AutoBuildRepository autoBuildRepository;
    private final ManualBuildItemRepository manualBuildItemRepository;
    private final AutoBuildItemRepository autoBuildItemRepository;
    private final OrderItemRepository orderItemRepository;

    /**
     * 회원의 장바구니 목록 조회 (GET /api/cart)
     */
    @Transactional(readOnly = true)
    public List<CartItemResponse> getCartItems(int userId) {
        List<CartItem> items = cartItemRepository.findByUser_UserId(userId);
        return items.stream().map(item -> {
            CartItemResponse.CartItemResponseBuilder builder = CartItemResponse.builder()
                    .cartItemId(item.getCartItemId())
                    .userId(userId)
                    .quantity(item.getQuantity());
            Product product = item.getProduct();
            ManualBuild manualBuild = item.getManualBuild();
            AutoBuild autoBuild = item.getAutoBuild();
            // [A] 일반 단품 상품
            if (product != null) {
                int usedByOthers = getUsedQuantityInCart(userId, product.getProductId(), item.getCartItemId());
                int remainingStock = Math.max(0, product.getStockQuantity() - usedByOthers);
                return builder.productId(product.getProductId())
                        .name(product.getName())
                        .price(product.getBasePrice())
                        .stockQuantity(remainingStock)
                        .thumbnailUrl(product.getThumbnailUrl())
                        .build();
            }
            // [B] 수동 커스텀 키보드 빌드
            if (manualBuild != null) {
                List<Product> parts = getManualBuildProducts(manualBuild.getManualBuildId());
                return populateBuildResponse(builder, parts, userId, item.getCartItemId())
                        .manualBuildId(manualBuild.getManualBuildId())
                        .buildType("MANUAL")
                        .name(manualBuild.getBuildName())
                        .price(manualBuild.getTotalPrice())
                        .includeAssembly(manualBuild.isIncludeAssembly())
                        .build();
            }
            // [C] 자동 추천 키보드 빌드
            if (autoBuild != null) {
                List<Product> parts = getAutoBuildProducts(autoBuild.getAutoBuildId());
                return populateBuildResponse(builder, parts, userId, item.getCartItemId())
                        .autoBuildId(autoBuild.getAutoBuildId())
                        .buildType("AUTO")
                        .name(autoBuild.getBuildName())
                        .price(autoBuild.getTotalPrice())
                        .includeAssembly(autoBuild.isIncludeAssembly())
                        .build();
            }
            return builder.build();
        }).toList();
    }

    /**
     * 장바구니 상품 추가 (POST /api/cart)
     */
    @Transactional
    public void addCartItem(int userId, CartAddRequest request) {
        User user = findUser(userId);
        int quantity = (request.getQuantity() != null && request.getQuantity() > 0) ? request.getQuantity() : 1;
        // [A] 수동 커스텀 빌드 저장
        if (request.getManualBuildId() != null) {
            ManualBuild manualBuild = manualBuildRepository.findById(request.getManualBuildId())
                    .orElseThrow(() -> new ResourceNotFoundException("커스텀 빌드를 찾을 수 없습니다."));
            List<Product> parts = getManualBuildProducts(manualBuild.getManualBuildId());
            Optional<CartItem> existing = cartItemRepository.findByUserAndManualBuild(user, manualBuild);
            addOrUpdateBuildItem(user, existing, parts, quantity, item -> item.setManualBuild(manualBuild));
            return;
        }
        // [B] 자동 추천 빌드 저장
        if (request.getAutoBuildId() != null) {
            AutoBuild autoBuild = autoBuildRepository.findById(request.getAutoBuildId())
                    .orElseThrow(() -> new ResourceNotFoundException("추천 빌드를 찾을 수 없습니다."));
            List<Product> parts = getAutoBuildProducts(autoBuild.getAutoBuildId());
            Optional<CartItem> existing = cartItemRepository.findByUserAndAutoBuild(user, autoBuild);
            addOrUpdateBuildItem(user, existing, parts, quantity, item -> item.setAutoBuild(autoBuild));
            return;
        }
        // [C] 일반 단품 상품 저장
        if (request.getProductId() != null) {
            Product product = findProduct(request.getProductId());
            validateOnSale(product);
            Optional<CartItem> existingItem = cartItemRepository.findByUserAndProduct(user, product);
            Integer excludeId = existingItem.map(CartItem::getCartItemId).orElse(null);
            int usedByOthers = getUsedQuantityInCart(userId, product.getProductId(), excludeId);
            int availableStock = Math.max(0, product.getStockQuantity() - usedByOthers);
            // 최대 재고수가 0개이면 바로 담기 거부
            if (availableStock <= 0) {
                throw new InvalidStateException("해당 상품은 현재 품절(또는 커스텀 키보드 부품으로 소진)되어 장바구니에 담을 수 없습니다.");
            }
            int currentQty = existingItem.map(CartItem::getQuantity).orElse(0);
            // 이미 최대치만큼 담겨 있으면 추가 거부
            if (currentQty >= availableStock) {
                throw new InvalidStateException(
                        "커스텀 키보드에 포함된 수량(" + usedByOthers + "개)으로 인해 이미 최대 수량(" + availableStock + "개)만큼 담겨 있습니다."
                );
            }
            // 재고 초과 시 자동으로 남은 최대 재고수로 보정
            int finalQuantity = Math.min(currentQty + quantity, availableStock);
            if (existingItem.isPresent()) {
                CartItem item = existingItem.get();
                item.setQuantity(finalQuantity);
                item.setUpdatedAt(LocalDateTime.now());
            } else {
                CartItem newItem = new CartItem();
                newItem.setUser(user);
                newItem.setProduct(product);
                newItem.setQuantity(finalQuantity);
                newItem.setCreatedAt(LocalDateTime.now());
                newItem.setUpdatedAt(LocalDateTime.now());
                cartItemRepository.save(newItem);
            }
            return;
        }
        throw new ResourceNotFoundException("추가할 상품 정보가 올바르지 않습니다.");
    }

    /**
     * 장바구니 상품 수량 변경 (PATCH /api/cart/{targetId})
     */
    @Transactional
    public void updateCartItemQuantity(int userId, int targetId, int quantity) {
        User user = findUser(userId);
        if (quantity <= 0) {
            throw new InvalidStateException("1개 이상의 수량만 지정할 수 있습니다.");
        }
        // cartItemId로 직접 조회 (없으면 예외)
        CartItem item = cartItemRepository.findByCartItemIdAndUser(targetId, user)
                .orElseThrow(() -> new ResourceNotFoundException("장바구니에 해당 상품이 없습니다."));
        // 단품 상품 수량 변경 검증
        if (item.getProduct() != null) {
            Product product = item.getProduct();
            validateOnSale(product);
            int usedByOthers = getUsedQuantityInCart(userId, product.getProductId(), item.getCartItemId());
            int availableStock = Math.max(0, product.getStockQuantity() - usedByOthers);
            if (availableStock <= 0) {
                throw new InvalidStateException("해당 상품의 재고가 모두 소진되었습니다.");
            }
            if (quantity > availableStock) {
                quantity = availableStock;
            }
        }
        // 커스텀 키보드(수동/자동) 수량 변경 검증
        else if (item.getManualBuild() != null || item.getAutoBuild() != null) {
            List<Product> parts = getBuildProducts(item);
            int availableStock = calculateAvailableStockForParts(userId, parts, item.getCartItemId());
            if (availableStock <= 0) {
                throw new InvalidStateException("부품 재고가 모두 소진되었습니다.");
            }
            if (quantity > availableStock) {
                quantity = availableStock;
            }
        }
        // 검증 통과 시 수량 업데이트
        item.setQuantity(quantity);
        item.setUpdatedAt(LocalDateTime.now());
    }

    /**
     * 장바구니 상품 단건 삭제 (DELETE /api/cart/{targetId})
     */
    @Transactional
    public void removeCartItem(int userId, int targetId) {
        User user = findUser(userId);
        // cartItemId로 조회 후 삭제 (없으면 예외)
        CartItem item = cartItemRepository.findByCartItemIdAndUser(targetId, user)
                .orElseThrow(() -> new ResourceNotFoundException("장바구니에 해당 상품이 없습니다."));
        deleteCartItemAndOrphanBuild(item);
    }

    /**
     * 장바구니 전체 비우기 (DELETE /api/cart)
     */
    @Transactional
    public void clearCart(int userId) {
        findUser(userId);
        List<CartItem> userItems = cartItemRepository.findByUser_UserId(userId);
        for (CartItem item : userItems) {
            deleteCartItemAndOrphanBuild(item);
        }
    }

    /**
     * 장바구니 항목 삭제 시, 커스텀 빌드와 부품 목록 테이블까지 연쇄 정리 삭제
     */
    private void deleteCartItemAndOrphanBuild(CartItem item) {
        ManualBuild mb = item.getManualBuild();
        AutoBuild ab = item.getAutoBuild();
        // 장바구니 행 먼저 삭제 후 즉시 DB 반영
        cartItemRepository.delete(item);
        cartItemRepository.flush();
        // 수동 커스텀 빌드: 주문 및 다른 장바구니에서 쓰이지 않는 경우 고아 빌드 데이터 완전 삭제
        if (mb != null) {
            int mbId = mb.getManualBuildId();
            boolean usedInOtherCart = cartItemRepository.existsByManualBuild_ManualBuildId(mbId);
            boolean usedInOrder = orderItemRepository.existsByManualBuild_ManualBuildId(mbId);
            if (!usedInOtherCart && !usedInOrder) {
                manualBuildItemRepository.deleteByManualBuild_ManualBuildId(mbId);
                manualBuildItemRepository.flush();
                manualBuildRepository.delete(mb);
            }
        }
        // 자동 추천 커스텀 빌드: 주문 및 다른 장바구니에서 쓰이지 않는 경우 고아 빌드 데이터 완전 삭제
        if (ab != null) {
            int abId = ab.getAutoBuildId();
            boolean usedInOtherCart = cartItemRepository.existsByAutoBuild_AutoBuildId(abId);
            boolean usedInOrder = orderItemRepository.existsByAutoBuild_AutoBuildId(abId);
            if (!usedInOtherCart && !usedInOrder) {
                autoBuildItemRepository.deleteByAutoBuild_AutoBuildId(abId);
                autoBuildItemRepository.flush();
                autoBuildRepository.delete(ab);
            }
        }
    }

    /**
     * 커스텀 빌드 장바구니 추가/수량합산 공통 처리
     */
    private void addOrUpdateBuildItem(User user, Optional<CartItem> existing, List<Product> parts,
                                      int quantity, Consumer<CartItem> buildSetter) {
        Integer excludeId = existing.map(CartItem::getCartItemId).orElse(null);
        int availableStock = calculateAvailableStockForParts(user.getUserId(), parts, excludeId);
        // 최대 재고수가 0개이면 바로 담기 거부
        if (availableStock <= 0) {
            throw new InvalidStateException("부품 재고가 부족하여 커스텀 키보드를 담을 수 없습니다.");
        }
        if (existing.isPresent()) {
            CartItem item = existing.get();
            int currentQty = item.getQuantity();
            // 이미 최대치만큼 담겨 있으면 추가 거부
            if (currentQty >= availableStock) {
                throw new InvalidStateException("부품 재고 부족으로 이미 최대 수량(" + availableStock + "개)만큼 담겨 있습니다.");
            }
            // 재고 초과 시 자동으로 남은 최대 재고수로 보정
            int finalQuantity = Math.min(currentQty + quantity, availableStock);
            item.setQuantity(finalQuantity);
            item.setUpdatedAt(LocalDateTime.now());
        } else {
            // 신규 담기 시에도 최대치로 자동 보정
            int finalQuantity = Math.min(quantity, availableStock);
            CartItem newItem = new CartItem();
            newItem.setUser(user);
            buildSetter.accept(newItem);
            newItem.setQuantity(finalQuantity);
            newItem.setCreatedAt(LocalDateTime.now());
            newItem.setUpdatedAt(LocalDateTime.now());
            cartItemRepository.save(newItem);
        }
    }

    /**
     * 커스텀 응답 공통 필드(재고, 썸네일, 부품 ID 목록) 설정
     */
    private CartItemResponse.CartItemResponseBuilder populateBuildResponse(
            CartItemResponse.CartItemResponseBuilder builder, List<Product> parts, int userId, int cartItemId) {
        int maxStock = calculateAvailableStockForParts(userId, parts, cartItemId);
        String thumb = extractThumbnail(parts);
        List<Integer> partIds = parts.stream().map(Product::getProductId).toList();
        return builder.stockQuantity(maxStock)
                .thumbnailUrl(thumb)
                .partProductIds(partIds);
    }

    /**
     * 부품 목록으로부터 현재 담을 수 있는 최대 조립 가능 수량(최솟값) 계산
     */
    private int calculateAvailableStockForParts(int userId, List<Product> parts, Integer excludeCartItemId) {
        return parts.stream()
                .filter(Objects::nonNull)
                .mapToInt(p -> {
                    int usedByOthers = getUsedQuantityInCart(userId, p.getProductId(), excludeCartItemId);
                    return Math.max(0, p.getStockQuantity() - usedByOthers);
                })
                .min()
                .orElse(0);
    }

    /**
     * 부품 목록 중 첫 번째 유효한 썸네일 이미지 URL 추출
     */
    private String extractThumbnail(List<Product> parts) {
        return parts.stream()
                .filter(p -> p != null && p.getThumbnailUrl() != null && !p.getThumbnailUrl().isBlank())
                .map(Product::getThumbnailUrl)
                .findFirst()
                .orElse("");
    }

    /**
     * 장바구니 아이템의 커스텀 빌드 부품 목록 추출
     */
    private List<Product> getBuildProducts(CartItem item) {
        if (item.getManualBuild() != null) {
            return getManualBuildProducts(item.getManualBuild().getManualBuildId());
        } else if (item.getAutoBuild() != null) {
            return getAutoBuildProducts(item.getAutoBuild().getAutoBuildId());
        }
        return Collections.emptyList();
    }

    private List<Product> getManualBuildProducts(int manualBuildId) {
        return manualBuildItemRepository.findByManualBuild_ManualBuildIdOrderByManualItemIdAsc(manualBuildId)
                .stream()
                .map(ManualBuildItem::getProduct)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<Product> getAutoBuildProducts(int autoBuildId) {
        return autoBuildItemRepository.findByAutoBuild_AutoBuildIdOrderByAutoItemIdAsc(autoBuildId)
                .stream()
                .map(AutoBuildItem::getProduct)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 현재 사용자의 장바구니에서 특정 상품(부품)이 사용 중인 총 수량 계산 (일반 단품 + 커스텀 키보드 부품 모두 포함)
     * @param excludeCartItemId 수정/조회 중인 자기 자신 항목의 cartItemId (신규 추가 시에는 null)
     */
    private int getUsedQuantityInCart(int userId, int productId, Integer excludeCartItemId) {
        List<CartItem> userItems = cartItemRepository.findByUser_UserId(userId);
        int totalUsed = 0;
        for (CartItem item : userItems) {
            // 자기 자신 항목은 제외 (현재 항목의 수량을 변경하거나 조회할 때 중복 차감 방지)
            if (excludeCartItemId != null && item.getCartItemId() == excludeCartItemId) {
                continue;
            }
            // 1. 일반 단품으로 담겨 있는 수량 합산
            if (item.getProduct() != null && item.getProduct().getProductId() == productId) {
                totalUsed += item.getQuantity();
            }
            // 2. 커스텀 키보드(수동/자동)에 부품으로 들어간 수량 합산
            else if (item.getManualBuild() != null || item.getAutoBuild() != null) {
                boolean contains = getBuildProducts(item).stream()
                        .anyMatch(p -> p.getProductId() == productId);
                if (contains) {
                    totalUsed += item.getQuantity();
                }
            }
        }
        return totalUsed;
    }

    private User findUser(int userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.warn("사용자를 찾을 수 없습니다. userId={}", userId);
                    return new ResourceNotFoundException("사용자를 찾을 수 없습니다.");
                });
    }

    private Product findProduct(int productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> {
                    log.warn("상품을 찾을 수 없습니다. productId={}", productId);
                    return new ResourceNotFoundException("상품을 찾을 수 없습니다.");
                });
    }
    private void validateOnSale(Product product) {
        if (!product.isActive() || product.isDeleted()) {
            log.warn("판매하지 않는 상품 장바구니 요청. productId={}", product.getProductId());
            throw new InvalidStateException("판매하지 않는 상품입니다.");
        }
    }
}