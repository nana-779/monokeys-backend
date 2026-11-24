package com.green.keyboard_project.service;

import com.green.keyboard_project.dto.cart.CartAddRequest;
import com.green.keyboard_project.dto.cart.CartItemResponse;
import com.green.keyboard_project.entity.*;
import com.green.keyboard_project.exceptions.InvalidStateException;
import com.green.keyboard_project.exceptions.ResourceNotFoundException;
import com.green.keyboard_project.repository.*;
import com.green.keyboard_project.repository.cart.CartItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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

    /**
     * 회원의 장바구니 목록 조회
     */
    @Transactional(readOnly = true)
    public List<CartItemResponse> getCartItems(int userId) {
        List<CartItem> items = cartItemRepository.findByUser_UserId(userId);
        return items.stream().map(item -> {
            Product product = item.getProduct();
            ManualBuild manualBuild = item.getManualBuild();
            AutoBuild autoBuild = item.getAutoBuild();

            CartItemResponse.CartItemResponseBuilder builder =
                    CartItemResponse.builder()
                            .cartItemId(item.getCartItemId())
                            .userId(userId)
                            .quantity(item.getQuantity());
            if (product != null) {
                // 커스텀에 쓰인 수량을 뺀 잔여 수량을 stockQuantity로 프론트에 내려줌
                int usedByOthers = getUsedQuantityInCart(userId, product.getProductId(), item.getCartItemId());
                int remainingStock = Math.max(0, product.getStockQuantity() - usedByOthers);
                builder.productId(product.getProductId())
                        .buildType(null)
                        .name(product.getName())
                        .price(product.getBasePrice())
                        .stockQuantity(remainingStock)
                        .thumbnailUrl(product.getThumbnailUrl());
            } else if (manualBuild != null) {
                List<ManualBuildItem> buildItems = manualBuildItemRepository
                        .findByManualBuild_ManualBuildIdOrderByManualItemIdAsc(
                                manualBuild.getManualBuildId());

                String thumb = buildItems.stream()
                        .map(ManualBuildItem :: getProduct)
                        .filter(p -> p != null && p.getThumbnailUrl() != null && !p.getThumbnailUrl().isBlank())
                        .map(Product::getThumbnailUrl)
                        .findFirst()
                        .orElse("");

                int maxStock = buildItems.stream()
                        .map(ManualBuildItem::getProduct)
                        .filter(p -> p != null)
                        .mapToInt(p -> {
                            // 이 커스텀 항목(item.getCartItemId())을 제외하고 다른 곳(단품/다른커스텀)에서 이미 쓰인 수량 차감
                            int usedByOthers = getUsedQuantityInCart(userId, p.getProductId(), item.getCartItemId());
                            return Math.max(0, p.getStockQuantity() - usedByOthers);
                        })
                        .min()
                        .orElse(0);

                List<Integer> partIds = buildItems.stream()
                        .map(i -> i.getProduct().getProductId())
                        .toList();

                builder.manualBuildId(manualBuild.getManualBuildId())
                        .buildType("MANUAL")
                        .name(manualBuild.getBuildName())
                        .price(manualBuild.getTotalPrice())
                        .stockQuantity(maxStock)
                        .thumbnailUrl(thumb)
                        .includeAssembly(manualBuild.isIncludeAssembly())
                        .partProductIds(partIds);
            } else if (autoBuild != null) {
                List<AutoBuildItem> buildItems = autoBuildItemRepository
                        .findByAutoBuild_AutoBuildIdOrderByAutoItemIdAsc(
                                autoBuild.getAutoBuildId());

                String thumb =buildItems.stream()
                        .map(AutoBuildItem :: getProduct)
                        .filter(p -> p != null && p.getThumbnailUrl() != null && !p.getThumbnailUrl().isBlank())
                        .map(Product::getThumbnailUrl)
                        .findFirst()
                        .orElse("");

                int maxStock = buildItems.stream()
                        .map(AutoBuildItem::getProduct)
                        .filter(p -> p != null)
                        .mapToInt(p -> {
                            // 이 커스텀 항목(item.getCartItemId())을 제외하고 다른 곳(단품/다른커스텀)에서 이미 쓰인 수량 차감
                            int usedByOthers = getUsedQuantityInCart(userId, p.getProductId(), item.getCartItemId());
                            return Math.max(0, p.getStockQuantity() - usedByOthers);
                        })
                        .min()
                        .orElse(0);

                List<Integer> partIds = buildItems.stream()
                        .map(i -> i.getProduct().getProductId())
                        .toList();

                builder.autoBuildId(autoBuild.getAutoBuildId())
                        .buildType("AUTO")
                        .name(autoBuild.getBuildName())
                        .price(autoBuild.getTotalPrice())
                        .stockQuantity(maxStock)
                        .thumbnailUrl(thumb)
                        .includeAssembly(autoBuild.isIncludeAssembly())
                        .partProductIds(partIds);
            }

            return  builder.build();
        }).toList();
    }

    /**
     * 장바구니 상품 추가
     */
    @Transactional
    public void addCartItem(int userId, CartAddRequest request) {
        User user = findUser(userId);
        int quantity = request.getQuantity() != null && request.getQuantity() > 0 ? request.getQuantity() : 1;
        // [A] 수동 커스텀 빌드 저장
        if (request.getManualBuildId() != null) {
            ManualBuild manualBuild = manualBuildRepository.findById(request.getManualBuildId())
                    .orElseThrow(() -> new ResourceNotFoundException("커스텀 빌드를 찾을 수 없습니다."));
            List<ManualBuildItem> buildItems = manualBuildItemRepository
                    .findByManualBuild_ManualBuildIdOrderByManualItemIdAsc(manualBuild.getManualBuildId());
            // 1. 이미 장바구니에 이 빌드가 있는지 조회 (동일 빌드 번호로 오므로 바로 조회됨)
            Optional<CartItem> existing = cartItemRepository.findByUserAndManualBuild(user, manualBuild);
            Integer excludeId = existing.map(CartItem::getCartItemId).orElse(null);
            // 2. 조립 가능한 최대 재고수 (단품 및 다른 빌드에 쓰인 수량 차감)
            int availableStock = buildItems.stream()
                    .map(ManualBuildItem::getProduct)
                    .filter(p -> p != null)
                    .mapToInt(p -> {
                        int usedByOthers = getUsedQuantityInCart(userId, p.getProductId(), excludeId);
                        return Math.max(0, p.getStockQuantity() - usedByOthers);
                    })
                    .min()
                    .orElse(0);
            // 3. 이미 장바구니에 있으면 수량만 합산 (삭제 코드 전혀 없음!)
            if (existing.isPresent()) {
                CartItem item = existing.get();
                int finalQuantity = item.getQuantity() + quantity;
                if (finalQuantity > availableStock) {
                    throw new InvalidStateException("부품 재고가 부족하여 수량을 더 추가할 수 없습니다. (최대 가능: " + availableStock + "개)");
                }
                item.setQuantity(finalQuantity);
                item.setUpdatedAt(LocalDateTime.now());
            } else {
                if (quantity > availableStock) {
                    throw new InvalidStateException("부품 재고가 부족하여 해당 수량을 담을 수 없습니다. (최대 가능: " + availableStock + "개)");
                }
                CartItem newItem = new CartItem();
                newItem.setUser(user);
                newItem.setManualBuild(manualBuild);
                newItem.setQuantity(quantity);
                newItem.setCreatedAt(LocalDateTime.now());
                newItem.setUpdatedAt(LocalDateTime.now());
                cartItemRepository.save(newItem);
            }
            return;
        }
        // [B] 자동 추천 빌드 저장
        if (request.getAutoBuildId() != null) {
            AutoBuild autoBuild = autoBuildRepository.findById(request.getAutoBuildId())
                    .orElseThrow(() -> new ResourceNotFoundException("추천 빌드를 찾을 수 없습니다."));
            List<AutoBuildItem> buildItems = autoBuildItemRepository
                    .findByAutoBuild_AutoBuildIdOrderByAutoItemIdAsc(autoBuild.getAutoBuildId());
            Optional<CartItem> existing = cartItemRepository.findByUserAndAutoBuild(user, autoBuild);
            Integer excludeId = existing.map(CartItem::getCartItemId).orElse(null);
            int availableStock = buildItems.stream()
                    .map(AutoBuildItem::getProduct)
                    .filter(p -> p != null)
                    .mapToInt(p -> {
                        int usedByOthers = getUsedQuantityInCart(userId, p.getProductId(), excludeId);
                        return Math.max(0, p.getStockQuantity() - usedByOthers);
                    })
                    .min()
                    .orElse(0);
            if (existing.isPresent()) {
                CartItem item = existing.get();
                int finalQuantity = item.getQuantity() + quantity;
                if (finalQuantity > availableStock) {
                    throw new InvalidStateException("부품 재고가 부족하여 수량을 더 추가할 수 없습니다. (최대 가능: " + availableStock + "개)");
                }
                item.setQuantity(finalQuantity);
                item.setUpdatedAt(LocalDateTime.now());
            } else {
                if (quantity > availableStock) {
                    throw new InvalidStateException("부품 재고가 부족하여 해당 수량을 담을 수 없습니다. (최대 가능: " + availableStock + "개)");
                }
                CartItem newItem = new CartItem();
                newItem.setUser(user);
                newItem.setAutoBuild(autoBuild);
                newItem.setQuantity(quantity);
                newItem.setCreatedAt(LocalDateTime.now());
                newItem.setUpdatedAt(LocalDateTime.now());
                cartItemRepository.save(newItem);
            }
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
            int currentQty = existingItem.map(CartItem::getQuantity).orElse(0);
            int totalRequested = currentQty + quantity;
            if (totalRequested > availableStock) {
                int remainCanAdd = Math.max(0, availableStock - currentQty);
                throw new InvalidStateException(
                        "커스텀 키보드에 포함된 수량(" + usedByOthers + "개)으로 인해 단품은 최대 "
                                + availableStock + "개까지만 담을 수 있습니다. (추가 가능: " + remainCanAdd + "개)"
                );
            }
            if (existingItem.isPresent()) {
                CartItem item = existingItem.get();
                item.setQuantity(totalRequested);
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
        // 1. cartItemId로 우선 조회, 없으면 productId로 조회
        CartItem item = cartItemRepository.findByCartItemIdAndUser(targetId, user)
                .orElseGet(() -> productRepository.findById(targetId)
                        .flatMap(product -> cartItemRepository.findByUserAndProduct(user, product))
                        .orElseThrow(() -> new ResourceNotFoundException("장바구니에 해당 상품이 없습니다.")));
        // ========================================================
        // [A] 일반 단품 상품 수량 변경 검증
        // ========================================================
        if (item.getProduct() != null) {
            Product product = item.getProduct();
            validateOnSale(product);
            // 다른 곳(커스텀 빌드 등)에서 이미 사용 중인 이 부품의 수량 차감
            int usedByOthers = getUsedQuantityInCart(userId, product.getProductId(), item.getCartItemId());
            int availableStock = Math.max(0, product.getStockQuantity() - usedByOthers);
            if (quantity > availableStock) {
                throw new InvalidStateException(
                        "커스텀 키보드에 포함된 수량(" + usedByOthers + "개)으로 인해 단품은 최대 "
                                + availableStock + "개까지만 변경할 수 있습니다."
                );
            }
        }
        // ========================================================
        // [B] 수동 커스텀 빌드 수량 변경 검증
        // ========================================================
        else if (item.getManualBuild() != null) {
            List<ManualBuildItem> buildItems = manualBuildItemRepository
                    .findByManualBuild_ManualBuildIdOrderByManualItemIdAsc(item.getManualBuild().getManualBuildId());
            // 부품 4개 각각에 대해 (실제 재고 - 단품/다른커스텀에 쓰인 수량) 중 최솟값 계산
            int availableStock = buildItems.stream()
                    .map(ManualBuildItem::getProduct)
                    .filter(p -> p != null)
                    .mapToInt(p -> {
                        int usedByOthers = getUsedQuantityInCart(userId, p.getProductId(), item.getCartItemId());
                        return Math.max(0, p.getStockQuantity() - usedByOthers);
                    })
                    .min()
                    .orElse(0);
            if (quantity > availableStock) {
                throw new InvalidStateException(
                        "부품 재고(일반 단품에 담긴 수량 포함)가 부족하여 최대 " + availableStock + "개까지만 변경할 수 있습니다."
                );
            }
        }
        // ========================================================
        // [C] 자동 추천 커스텀 빌드 수량 변경 검증
        // ========================================================
        else if (item.getAutoBuild() != null) {
            List<AutoBuildItem> buildItems = autoBuildItemRepository
                    .findByAutoBuild_AutoBuildIdOrderByAutoItemIdAsc(item.getAutoBuild().getAutoBuildId());
            int availableStock = buildItems.stream()
                    .map(AutoBuildItem::getProduct)
                    .filter(p -> p != null)
                    .mapToInt(p -> {
                        int usedByOthers = getUsedQuantityInCart(userId, p.getProductId(), item.getCartItemId());
                        return Math.max(0, p.getStockQuantity() - usedByOthers);
                    })
                    .min()
                    .orElse(0);
            if (quantity > availableStock) {
                throw new InvalidStateException(
                        "부품 재고(일반 단품에 담긴 수량 포함)가 부족하여 최대 " + availableStock + "개까지만 변경할 수 있습니다."
                );
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
        // 1. cartItemId로 삭제
        Optional<CartItem> byCartItemId = cartItemRepository.findByCartItemIdAndUser(targetId, user);
        if (byCartItemId.isPresent()) {
            cartItemRepository.delete(byCartItemId.get());
            return;
        }
        // 2. productId로 넘어왔을 경우 대비
        productRepository.findById(targetId)
                .flatMap(product -> cartItemRepository.findByUserAndProduct(user, product))
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
            // 2. 다른 수동 커스텀 키보드에 부품으로 들어간 수량 합산
            else if (item.getManualBuild() != null) {
                boolean contains = manualBuildItemRepository
                        .findByManualBuild_ManualBuildIdOrderByManualItemIdAsc(item.getManualBuild().getManualBuildId())
                        .stream()
                        .anyMatch(i -> i.getProduct().getProductId() == productId);
                if (contains) {
                    totalUsed += item.getQuantity();
                }
            }
            // 3. 다른 자동 추천 키보드에 부품으로 들어간 수량 합산
            else if (item.getAutoBuild() != null) {
                boolean contains = autoBuildItemRepository
                        .findByAutoBuild_AutoBuildIdOrderByAutoItemIdAsc(item.getAutoBuild().getAutoBuildId())
                        .stream()
                        .anyMatch(i -> i.getProduct().getProductId() == productId);
                if (contains) {
                    totalUsed += item.getQuantity();
                }
            }
        }
        return totalUsed;
    }
}