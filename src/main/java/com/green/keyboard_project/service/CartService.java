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
                builder.productId(product.getProductId())
                        .buildType(null)
                        .name(product.getName())
                        .price(product.getBasePrice())
                        .stockQuantity(product.getStockQuantity())
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

                // 재고수 설정 (부품중 재고수가 제일 적은 걸 최대 재고수로)
                int maxStock = buildItems.stream()
                        .map(ManualBuildItem::getProduct)
                        .filter(p -> p != null)
                        .mapToInt(Product::getStockQuantity)
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
                        .mapToInt(Product::getStockQuantity)
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
            // 1. 이번에 담으려는 빌드의 부품 4개 ID 및 조립 옵션 추출
            List<ManualBuildItem> newBuildItems = manualBuildItemRepository
                    .findByManualBuild_ManualBuildIdOrderByManualItemIdAsc(manualBuild.getManualBuildId());
            Set<Integer> newProductIds = newBuildItems.stream()
                    .map(item -> item.getProduct().getProductId())
                    .collect(Collectors.toSet());
            boolean newAssembly = manualBuild.isIncludeAssembly();
            // 조립 가능한 최대 재고수(부품 중 최소 재고)
            int availableStock = newBuildItems.stream()
                    .map(ManualBuildItem::getProduct)
                    .filter(p -> p != null)
                    .mapToInt(Product::getStockQuantity)
                    .min()
                    .orElse(0);
            // 2. 이미 장바구니에 "동일한 부품 조합"의 수동 빌드가 있는지 탐색
            List<CartItem> userCartItems = cartItemRepository.findByUser_UserId(userId);
            CartItem existingMatch = null;
            for (CartItem ci : userCartItems) {
                if (ci.getManualBuild() != null) {
                    ManualBuild existingBuild = ci.getManualBuild();
                    // 조립 옵션이 같을 때 부품 4개 ID가 완전히 일치하는지 비교
                    if (existingBuild.isIncludeAssembly() == newAssembly) {
                        Set<Integer> existProductIds = manualBuildItemRepository
                                .findByManualBuild_ManualBuildIdOrderByManualItemIdAsc(existingBuild.getManualBuildId())
                                .stream()
                                .map(item -> item.getProduct().getProductId())
                                .collect(Collectors.toSet());
                        if (existProductIds.equals(newProductIds)) {
                            existingMatch = ci; // 👈 똑같은 부품 조합 발견!
                            break;
                        }
                    }
                }
            }
            // 3. 동일한 조합이 이미 있으면 기존 장바구니 항목의 수량만 증가
            if (existingMatch != null) {
                int finalQuantity = existingMatch.getQuantity() + quantity;
                if (finalQuantity > availableStock) {
                    throw new InvalidStateException("부품 재고가 부족하여 수량을 더 추가할 수 없습니다. (최대 가능: " + availableStock + "개)");
                }
                existingMatch.setQuantity(finalQuantity);
                existingMatch.setUpdatedAt(LocalDateTime.now());
                // 새로 임시 발급되었던 중복 빌드는 DB에서 깔끔하게 정리 삭제
                if (manualBuild.getManualBuildId() != existingMatch.getManualBuild().getManualBuildId()) {
                    manualBuildItemRepository.deleteByManualBuild_ManualBuildId(manualBuild.getManualBuildId());
                    manualBuildRepository.delete(manualBuild);
                }
            } else {
                // 새로운 조합이면 새로 장바구니에 등록
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
        // [B] 자동 추천 빌드 저장 (수동과 동일한 조합 검사)
        if (request.getAutoBuildId() != null) {
            AutoBuild autoBuild = autoBuildRepository.findById(request.getAutoBuildId())
                    .orElseThrow(() -> new ResourceNotFoundException("추천 빌드를 찾을 수 없습니다."));
            List<AutoBuildItem> newBuildItems = autoBuildItemRepository
                    .findByAutoBuild_AutoBuildIdOrderByAutoItemIdAsc(autoBuild.getAutoBuildId());
            Set<Integer> newProductIds = newBuildItems.stream()
                    .map(item -> item.getProduct().getProductId())
                    .collect(Collectors.toSet());
            boolean newAssembly = autoBuild.isIncludeAssembly();
            int availableStock = newBuildItems.stream()
                    .map(AutoBuildItem::getProduct)
                    .filter(p -> p != null)
                    .mapToInt(Product::getStockQuantity)
                    .min()
                    .orElse(0);
            List<CartItem> userCartItems = cartItemRepository.findByUser_UserId(userId);
            CartItem existingMatch = null;
            for (CartItem ci : userCartItems) {
                if (ci.getAutoBuild() != null) {
                    AutoBuild existingBuild = ci.getAutoBuild();
                    if (existingBuild.isIncludeAssembly() == newAssembly) {
                        Set<Integer> existProductIds = autoBuildItemRepository
                                .findByAutoBuild_AutoBuildIdOrderByAutoItemIdAsc(existingBuild.getAutoBuildId())
                                .stream()
                                .map(item -> item.getProduct().getProductId())
                                .collect(Collectors.toSet());
                        if (existProductIds.equals(newProductIds)) {
                            existingMatch = ci;
                            break;
                        }
                    }
                }
            }
            if (existingMatch != null) {
                int finalQuantity = existingMatch.getQuantity() + quantity;
                if (finalQuantity > availableStock) {
                    throw new InvalidStateException("부품 재고가 부족하여 수량을 더 추가할 수 없습니다. (최대 가능: " + availableStock + "개)");
                }
                existingMatch.setQuantity(finalQuantity);
                existingMatch.setUpdatedAt(LocalDateTime.now());
                if (autoBuild.getAutoBuildId() != existingMatch.getAutoBuild().getAutoBuildId()) {
                    autoBuildItemRepository.deleteByAutoBuild_AutoBuildId(autoBuild.getAutoBuildId());
                    autoBuildRepository.delete(autoBuild);
                }
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
            if (quantity > product.getStockQuantity()) {
                throw new InvalidStateException(product.getName() + "의 현재 재고수는 " + product.getStockQuantity() + "개 입니다.");
            }
            Optional<CartItem> existing = cartItemRepository.findByUserAndProduct(user, product);
            if (existing.isPresent()) {
                CartItem item = existing.get();
                if (item.getQuantity() + quantity > product.getStockQuantity()) {
                    throw new InvalidStateException(product.getName() + "의 현재 재고수는 " + product.getStockQuantity() + "개 입니다.");
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
            throw new InvalidStateException("1개 이상의 상품만 추가할 수 있습니다.");
        }
        // 1. cartItemId로 우선 조회, 없으면 productId로 조회
        CartItem item = cartItemRepository.findByCartItemIdAndUser(targetId, user)
                .orElseGet(() -> productRepository.findById(targetId)
                        .flatMap(product -> cartItemRepository.findByUserAndProduct(user, product))
                        .orElseThrow(() -> new ResourceNotFoundException("장바구니에 해당 상품이 없습니다.")));
        if (item.getProduct() != null) {
            Product product = item.getProduct();
            validateOnSale(product);
            if (quantity > product.getStockQuantity()) {
                throw new InvalidStateException("재고가 부족합니다.");
            }
        }
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
}