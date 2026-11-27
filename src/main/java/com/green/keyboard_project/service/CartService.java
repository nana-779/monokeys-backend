package com.green.keyboard_project.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.green.keyboard_project.dto.cart.CartAddRequest;
import com.green.keyboard_project.dto.cart.CartBuildPartResponse;
import com.green.keyboard_project.dto.cart.CartItemResponse;
import com.green.keyboard_project.dto.cart.CartOrderLine;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
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
    private final OrderItemRepository orderItemRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 회원의 장바구니 목록 조회 (GET /api/cart)
     * - 장바구니 항목·빌드 부품·품목별 사용 수량을 일괄 로드해 N+1 쿼리를 방지합니다.
     */
    @Transactional(readOnly = true)
    public List<CartItemResponse> getCartItems(int userId) {
        CartSnapshot snapshot = loadCartSnapshot(userId);
        return snapshot.items().stream().map(item -> {
            CartItemResponse.CartItemResponseBuilder builder = CartItemResponse.builder()
                    .cartItemId(item.getCartItemId())
                    .userId(userId)
                    .quantity(item.getQuantity());

            Product product = item.getProduct();
            ManualBuild manualBuild = item.getManualBuild();
            AutoBuild autoBuild = item.getAutoBuild();

            // [A] 일반 단품 및 커스텀 키캡 상품
            if (product != null) {
                int remainingStock = snapshot.availableStock(product, item);
                return builder.productId(product.getProductId())
                        .name(product.getName())
                        .price(product.getBasePrice())
                        .stockQuantity(remainingStock)
                        .thumbnailUrl(product.getThumbnailUrl())
                        .customDesignData(item.getCustomDesignData())
                        .build();
            }

            // [B] 수동 커스텀 키보드 빌드
            if (manualBuild != null) {
                List<BuildPartEntry> partEntries = snapshot.partEntriesOf(item);
                return populateBuildResponse(builder, snapshot, partEntries, item)
                        .manualBuildId(manualBuild.getManualBuildId())
                        .buildType("MANUAL")
                        .name(manualBuild.getBuildName())
                        .price(manualBuild.getTotalPrice())
                        .includeAssembly(manualBuild.isIncludeAssembly())
                        .build();
            }

            // [C] 자동 추천 키보드 빌드
            if (autoBuild != null) {
                List<BuildPartEntry> partEntries = snapshot.partEntriesOf(item);
                return populateBuildResponse(builder, snapshot, partEntries, item)
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
        CartSnapshot snapshot = loadCartSnapshot(userId);

        // [A] 수동 커스텀 키보드 빌드 저장
        if (request.getManualBuildId() != null) {
            ManualBuild manualBuild = manualBuildRepository.findById(request.getManualBuildId())
                    .orElseThrow(() -> new ResourceNotFoundException("커스텀 빌드를 찾을 수 없습니다."));

            if (manualBuild.getUser() != null && manualBuild.getUser().getUserId() != user.getUserId()) {
                throw new InvalidStateException("본인이 생성한 커스텀 빌드만 장바구니에 담을 수 있습니다.");
            }

            Optional<CartItem> existing = snapshot.items().stream()
                    .filter(i -> i.getManualBuild() != null
                            && i.getManualBuild().getManualBuildId() == manualBuild.getManualBuildId())
                    .findFirst();
            List<Product> parts = snapshot.manualPartsByBuildId().containsKey(manualBuild.getManualBuildId())
                    ? snapshot.manualPartsByBuildId().get(manualBuild.getManualBuildId()).stream()
                            .map(BuildPartEntry::product).toList()
                    : getManualBuildProducts(manualBuild.getManualBuildId());
            addOrUpdateBuildItem(user, snapshot, existing, parts, quantity, item -> item.setManualBuild(manualBuild));
            return;
        }

        // [B] 자동 추천 키보드 빌드 저장
        if (request.getAutoBuildId() != null) {
            AutoBuild autoBuild = autoBuildRepository.findById(request.getAutoBuildId())
                    .orElseThrow(() -> new ResourceNotFoundException("추천 빌드를 찾을 수 없습니다."));

            if (autoBuild.getUser() != null && autoBuild.getUser().getUserId() != user.getUserId()) {
                throw new InvalidStateException("본인이 생성한 커스텀 빌드만 장바구니에 담을 수 있습니다.");
            }

            Optional<CartItem> existing = snapshot.items().stream()
                    .filter(i -> i.getAutoBuild() != null
                            && i.getAutoBuild().getAutoBuildId() == autoBuild.getAutoBuildId())
                    .findFirst();
            List<Product> parts = snapshot.autoPartsByBuildId().containsKey(autoBuild.getAutoBuildId())
                    ? snapshot.autoPartsByBuildId().get(autoBuild.getAutoBuildId()).stream()
                            .map(BuildPartEntry::product).toList()
                    : getAutoBuildProducts(autoBuild.getAutoBuildId());
            addOrUpdateBuildItem(user, snapshot, existing, parts, quantity, item -> item.setAutoBuild(autoBuild));
            return;
        }

        // [C] 상품 (일반 단품 및 커스텀 키캡) 저장
        if (request.getProductId() != null) {
            Product product = findProduct(request.getProductId());
            validateOnSale(product);

            String requestDesign = (request.getCustomDesignData() != null && !request.getCustomDesignData().isBlank())
                    ? request.getCustomDesignData().trim() : null;

            // 1. 이미 로드한 스냅샷에서 동일 상품 행 추출
            Optional<CartItem> existingItem = snapshot.items().stream()
                    .filter(item -> item.getProduct() != null && item.getProduct().getProductId() == product.getProductId())
                    .filter(item -> isSameDesign(requestDesign, item.getCustomDesignData()))
                    .findFirst();

            // 2. 잔여 재고 계산 (동일 품목 행 제외)
            int availableStock = snapshot.availableStock(product, existingItem.orElse(null));

            if (availableStock <= 0) {
                throw new InvalidStateException("해당 상품은 현재 품절(또는 커스텀 상품/키보드로 소진)되어 장바구니에 담을 수 없습니다.");
            }

            int currentQty = existingItem.map(CartItem::getQuantity).orElse(0);
            if (currentQty >= availableStock) {
                throw new InvalidStateException("이미 최대 주문 가능 수량(" + availableStock + "개)만큼 담겨 있습니다.");
            }

            int finalQuantity = Math.min(currentQty + quantity, availableStock);

            if (existingItem.isPresent()) {
                // [동일 디자인 존재 -> 기존 행의 수량만 증가!]
                CartItem item = existingItem.get();
                item.setQuantity(finalQuantity);
                if (requestDesign != null) {
                    item.setCustomDesignData(requestDesign); // 최신 이미지 URL 등으로 갱신
                }
                item.setUpdatedAt(LocalDateTime.now());
            } else {
                // [새로운 디자인 또는 최초 등록 -> 신규 행 생성!]
                CartItem newItem = new CartItem();
                newItem.setUser(user);
                newItem.setProduct(product);
                newItem.setCustomDesignData(requestDesign);
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
        findUser(userId);
        if (quantity <= 0) {
            throw new InvalidStateException("1개 이상의 수량만 지정할 수 있습니다.");
        }

        CartSnapshot snapshot = loadCartSnapshot(userId);
        CartItem item = snapshot.items().stream()
                .filter(i -> i.getCartItemId() == targetId)
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("장바구니에 해당 상품이 없습니다."));

        // 단품 및 커스텀 키캡 수량 변경 검증
        if (item.getProduct() != null) {
            Product product = item.getProduct();
            validateOnSale(product);
            int availableStock = snapshot.availableStock(product, item);
            if (availableStock <= 0) {
                throw new InvalidStateException("해당 상품의 재고가 모두 소진되었습니다.");
            }
            if (quantity > availableStock) {
                quantity = availableStock;
            }
        }
        // 커스텀 키보드(수동/자동) 수량 변경 검증
        else if (item.getManualBuild() != null || item.getAutoBuild() != null) {
            List<Product> parts = snapshot.partsOf(item);
            int availableStock = snapshot.availableBuildStock(parts, item);
            if (availableStock <= 0) {
                throw new InvalidStateException("부품 재고가 모두 소진되었습니다.");
            }
            if (quantity > availableStock) {
                quantity = availableStock;
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
     * 주문 생성 시 요청된 cartItemId 목록을 서버 DB 기준으로 검증·해석합니다.
     * - 단품/커스텀 키캡의 product_id 누적 수량과 커스텀 빌드의 공유 부품 재고까지 합산 검증합니다.
     */
    @Transactional(readOnly = true)
    public List<CartOrderLine> resolveCartItemsForOrder(int userId, Collection<Integer> cartItemIds) {
        findUser(userId);
        if (cartItemIds == null || cartItemIds.isEmpty()) {
            throw new InvalidStateException("주문할 장바구니 항목이 비어 있습니다.");
        }

        Set<Integer> requestedIds = new LinkedHashSet<>();
        for (Integer id : cartItemIds) {
            if (id == null || id <= 0) {
                throw new InvalidStateException("올바르지 않은 장바구니 항목 ID입니다.");
            }
            requestedIds.add(id);
        }

        Map<Integer, CartItem> itemsById = cartItemRepository.findWithDetailsByUserIdAndCartItemIds(userId, requestedIds)
                .stream()
                .collect(Collectors.toMap(CartItem::getCartItemId, Function.identity()));

        List<CartItem> orderedItems = new ArrayList<>(requestedIds.size());
        Set<Integer> manualBuildIds = new HashSet<>();
        Set<Integer> autoBuildIds = new HashSet<>();
        for (Integer id : requestedIds) {
            CartItem item = itemsById.get(id);
            if (item == null) {
                throw new ResourceNotFoundException("장바구니에서 주문 항목을 찾을 수 없습니다. (cartItemId=" + id + ")");
            }
            orderedItems.add(item);
            if (item.getManualBuild() != null) manualBuildIds.add(item.getManualBuild().getManualBuildId());
            if (item.getAutoBuild() != null) autoBuildIds.add(item.getAutoBuild().getAutoBuildId());
        }

        Map<Integer, List<BuildPartEntry>> manualPartsById = loadManualPartsByBuildIds(manualBuildIds);
        Map<Integer, List<BuildPartEntry>> autoPartsById = loadAutoPartsByBuildIds(autoBuildIds);

        Map<Integer, Integer> requiredByProductId = new LinkedHashMap<>();
        Map<Integer, Product> productCache = new HashMap<>();
        List<CartOrderLine> resolvedLines = new ArrayList<>(orderedItems.size());

        for (CartItem item : orderedItems) {
            if (item.getQuantity() <= 0) {
                throw new InvalidStateException("주문 수량은 1개 이상이어야 합니다.");
            }
            Product product = item.getProduct();
            ManualBuild manualBuild = item.getManualBuild();
            AutoBuild autoBuild = item.getAutoBuild();

            if (product != null) {
                validateOnSale(product);
                requiredByProductId.merge(product.getProductId(), item.getQuantity(), Integer::sum);
                productCache.putIfAbsent(product.getProductId(), product);
                resolvedLines.add(new CartOrderLine(
                        item.getCartItemId(),
                        item,
                        product,
                        null,
                        null,
                        item.getCustomDesignData(),
                        product.getName(),
                        product.getBasePrice(),
                        item.getQuantity(),
                        List.of(product)
                ));
                continue;
            }

            if (manualBuild != null) {
                resolvedLines.add(resolveBuildOrderLine(
                        item,
                        userId,
                        manualBuild.getUser(),
                        manualPartsById.getOrDefault(manualBuild.getManualBuildId(), List.of()),
                        manualBuild.getBuildName(),
                        manualBuild.getTotalPrice(),
                        manualBuild,
                        null,
                        requiredByProductId,
                        productCache
                ));
                continue;
            }

            if (autoBuild != null) {
                resolvedLines.add(resolveBuildOrderLine(
                        item,
                        userId,
                        autoBuild.getUser(),
                        autoPartsById.getOrDefault(autoBuild.getAutoBuildId(), List.of()),
                        autoBuild.getBuildName(),
                        autoBuild.getTotalPrice(),
                        null,
                        autoBuild,
                        requiredByProductId,
                        productCache
                ));
                continue;
            }

            throw new InvalidStateException("유효하지 않은 장바구니 항목입니다. (cartItemId=" + item.getCartItemId() + ")");
        }

        for (Map.Entry<Integer, Integer> entry : requiredByProductId.entrySet()) {
            Product product = productCache.get(entry.getKey());
            int required = entry.getValue();
            if (product.getStockQuantity() < required) {
                throw new InvalidStateException(
                        "상품 재고가 부족합니다. (" + product.getName() + ", 현재 재고: " + product.getStockQuantity() + "개)"
                );
            }
        }

        return resolvedLines;
    }

    /**
     * 검증·해석된 장바구니 주문 항목들의 실물 상품(단품/키캡 또는 빌드 부품 4종) 재고를 차감합니다.
     */
    @Transactional
    public void deductStockForOrderLines(Collection<CartOrderLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        for (CartOrderLine line : lines) {
            if (line.quantity() <= 0 || line.stockTargets() == null) {
                continue;
            }
            Set<Integer> seenProductIds = new HashSet<>();
            for (Product target : line.stockTargets()) {
                if (target == null || !seenProductIds.add(target.getProductId())) {
                    continue;
                }
                if (target.getStockQuantity() < line.quantity()) {
                    throw new InvalidStateException(
                            "상품 재고가 부족합니다. (" + target.getName() + ", 현재 재고: " + target.getStockQuantity() + "개)"
                    );
                }
                target.setStockQuantity(target.getStockQuantity() - line.quantity());
            }
        }
    }

    /**
     * 결제 실패/취소 시 주문 항목(단품·커스텀 키캡·수동/자동 빌드)의 재고를 일괄 복구합니다.
     */
    @Transactional
    public void restoreStockForOrderItems(Collection<OrderItem> orderItems) {
        if (orderItems == null || orderItems.isEmpty()) {
            return;
        }
        Set<Integer> manualBuildIds = new HashSet<>();
        Set<Integer> autoBuildIds = new HashSet<>();
        for (OrderItem item : orderItems) {
            if (item == null || item.getQuantity() <= 0) {
                continue;
            }
            if (item.getManualBuild() != null) manualBuildIds.add(item.getManualBuild().getManualBuildId());
            if (item.getAutoBuild() != null) autoBuildIds.add(item.getAutoBuild().getAutoBuildId());
        }

        Map<Integer, List<BuildPartEntry>> manualPartsById = loadManualPartsByBuildIds(manualBuildIds);
        Map<Integer, List<BuildPartEntry>> autoPartsById = loadAutoPartsByBuildIds(autoBuildIds);

        for (OrderItem item : orderItems) {
            if (item == null || item.getQuantity() <= 0) {
                continue;
            }
            if (item.getProduct() != null) {
                restoreProductsStock(List.of(item.getProduct()), item.getQuantity());
            } else if (item.getManualBuild() != null) {
                List<Product> parts = manualPartsById.getOrDefault(item.getManualBuild().getManualBuildId(), List.of())
                        .stream().map(BuildPartEntry::product).toList();
                restoreProductsStock(parts, item.getQuantity());
            } else if (item.getAutoBuild() != null) {
                List<Product> parts = autoPartsById.getOrDefault(item.getAutoBuild().getAutoBuildId(), List.of())
                        .stream().map(BuildPartEntry::product).toList();
                restoreProductsStock(parts, item.getQuantity());
            }
        }
    }

    /**
     * 주문 결제가 완료된 장바구니 항목들만 정리합니다.
     * - 이미 OrderItem에 연결된 빌드는 deleteCartItemAndOrphanBuild 내부에서 보존됩니다.
     */
    @Transactional
    public void removeOrderedCartItems(int userId, Collection<Integer> cartItemIds) {
        if (cartItemIds == null || cartItemIds.isEmpty()) {
            return;
        }
        List<CartItem> targets = cartItemRepository.findWithDetailsByUserIdAndCartItemIds(userId, cartItemIds);
        for (CartItem item : targets) {
            deleteCartItemAndOrphanBuild(item);
        }
    }

    private CartOrderLine resolveBuildOrderLine(
            CartItem item,
            int userId,
            User owner,
            List<BuildPartEntry> entries,
            String buildName,
            int totalPrice,
            ManualBuild manualBuild,
            AutoBuild autoBuild,
            Map<Integer, Integer> requiredByProductId,
            Map<Integer, Product> productCache
    ) {
        if (owner != null && owner.getUserId() != userId) {
            throw new InvalidStateException("본인이 생성한 커스텀 빌드만 주문할 수 있습니다.");
        }
        List<Product> parts = entries.stream()
                .map(BuildPartEntry::product)
                .filter(Objects::nonNull)
                .toList();
        if (parts.isEmpty()) {
            throw new InvalidStateException("커스텀 키보드 구성 부품 정보가 올바르지 않습니다.");
        }

        Set<Integer> seenPartIds = new HashSet<>();
        for (Product part : parts) {
            validateOnSale(part);
            if (seenPartIds.add(part.getProductId())) {
                requiredByProductId.merge(part.getProductId(), item.getQuantity(), Integer::sum);
                productCache.putIfAbsent(part.getProductId(), part);
            }
        }

        return new CartOrderLine(
                item.getCartItemId(),
                item,
                null,
                manualBuild,
                autoBuild,
                null,
                buildName,
                totalPrice,
                item.getQuantity(),
                parts
        );
    }

    private void restoreProductsStock(Collection<Product> products, int quantity) {
        Set<Integer> seenProductIds = new HashSet<>();
        for (Product product : products) {
            if (product != null && seenProductIds.add(product.getProductId())) {
                product.setStockQuantity(product.getStockQuantity() + quantity);
            }
        }
    }

    private Map<Integer, List<BuildPartEntry>> loadManualPartsByBuildIds(Collection<Integer> buildIds) {
        return groupBuildParts(
                buildIds,
                manualBuildItemRepository::findWithProductByManualBuildIds,
                m -> m.getManualBuild().getManualBuildId(),
                ManualBuildItem::getProduct,
                ManualBuildItem::getUnitPrice
        );
    }

    private Map<Integer, List<BuildPartEntry>> loadAutoPartsByBuildIds(Collection<Integer> buildIds) {
        return groupBuildParts(
                buildIds,
                autoBuildItemRepository::findWithProductByAutoBuildIds,
                a -> a.getAutoBuild().getAutoBuildId(),
                AutoBuildItem::getProduct,
                AutoBuildItem::getUnitPrice
        );
    }

    private <T> Map<Integer, List<BuildPartEntry>> groupBuildParts(
            Collection<Integer> buildIds,
            Function<Collection<Integer>, List<T>> fetcher,
            Function<T, Integer> buildIdExtractor,
            Function<T, Product> productExtractor,
            Function<T, Integer> priceExtractor
    ) {
        if (buildIds == null || buildIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return fetcher.apply(buildIds).stream()
                .filter(row -> productExtractor.apply(row) != null)
                .collect(Collectors.groupingBy(
                        buildIdExtractor,
                        Collectors.mapping(
                                row -> new BuildPartEntry(productExtractor.apply(row), priceExtractor.apply(row)),
                                Collectors.toList()
                        )
                ));
    }

    /**
     * 커스텀 키캡 디자인 일치 여부 비교 헬퍼 메서드
     * (이미지 URL의 UUID 난수를 제외한 핵심 디자인 속성들을 비교)
     */
    private boolean isSameDesign(String designA, String designB) {
        if (Objects.equals(designA, designB)) {
            return true;
        }
        if (designA == null || designB == null || designA.isBlank() || designB.isBlank()) {
            return false;
        }
        try {
            JsonNode nodeA = objectMapper.readTree(designA);
            JsonNode nodeB = objectMapper.readTree(designB);

            if (nodeA.has("keyType") || nodeA.has("customText") || nodeA.has("textLayers")) {
                boolean keyTypeMatch = Objects.equals(nodeA.path("keyType").asText(null), nodeB.path("keyType").asText(null));
                boolean keySizeMatch = Objects.equals(nodeA.path("keySize").asText(null), nodeB.path("keySize").asText(null));
                boolean baseColorMatch = Objects.equals(nodeA.path("baseColor").asText(null), nodeB.path("baseColor").asText(null));
                boolean bgColorMatch = Objects.equals(nodeA.path("backgroundColor").asText(null), nodeB.path("backgroundColor").asText(null));
                boolean textMatch = Objects.equals(nodeA.path("customText").asText(null), nodeB.path("customText").asText(null));
                boolean textLayersMatch = Objects.equals(nodeA.path("textLayers"), nodeB.path("textLayers"));

                return keyTypeMatch && keySizeMatch && baseColorMatch && bgColorMatch && textMatch && textLayersMatch;
            }
            return nodeA.equals(nodeB);
        } catch (Exception e) {
            return Objects.equals(designA.trim(), designB.trim());
        }
    }

    /**
     * 장바구니 항목 삭제 시, 커스텀 빌드와 부품 목록 테이블까지 연쇄 정리 삭제
     */
    private void deleteCartItemAndOrphanBuild(CartItem item) {
        ManualBuild mb = item.getManualBuild();
        AutoBuild ab = item.getAutoBuild();

        cartItemRepository.delete(item);
        cartItemRepository.flush();

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
    private void addOrUpdateBuildItem(User user, CartSnapshot snapshot, Optional<CartItem> existing,
                                      List<Product> parts, int quantity, Consumer<CartItem> buildSetter) {
        int availableStock = snapshot.availableBuildStock(parts, existing.orElse(null));

        if (availableStock <= 0) {
            throw new InvalidStateException("부품 재고가 부족하여 커스텀 키보드를 담을 수 없습니다.");
        }

        if (existing.isPresent()) {
            CartItem item = existing.get();
            int currentQty = item.getQuantity();
            if (currentQty >= availableStock) {
                throw new InvalidStateException("부품 재고 부족으로 이미 최대 수량(" + availableStock + "개)만큼 담겨 있습니다.");
            }
            int finalQuantity = Math.min(currentQty + quantity, availableStock);
            item.setQuantity(finalQuantity);
            item.setUpdatedAt(LocalDateTime.now());
        } else {
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
     * 커스텀 응답 공통 필드(재고, 썸네일, 부품 ID·상세 목록) 설정
     */
    private CartItemResponse.CartItemResponseBuilder populateBuildResponse(
            CartItemResponse.CartItemResponseBuilder builder, CartSnapshot snapshot,
            List<BuildPartEntry> partEntries, CartItem currentItem) {
        List<Product> parts = partEntries.stream()
                .map(BuildPartEntry::product)
                .filter(Objects::nonNull)
                .toList();
        int maxStock = snapshot.availableBuildStock(parts, currentItem);
        String thumb = extractThumbnail(parts);
        List<Integer> partIds = parts.stream().map(Product::getProductId).toList();
        List<CartBuildPartResponse> partDetails = partEntries.stream()
                .filter(entry -> entry.product() != null)
                .map(entry -> CartBuildPartResponse.builder()
                        .productId(entry.product().getProductId())
                        .partType(entry.product().getDerivedPartType())
                        .name(entry.product().getName())
                        .unitPrice(entry.unitPrice())
                        .build())
                .toList();
        return builder.stockQuantity(maxStock)
                .thumbnailUrl(thumb)
                .partProductIds(partIds)
                .partDetails(partDetails);
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

    private List<Product> getManualBuildProducts(int manualBuildId) {
        return manualBuildItemRepository.findWithProductByManualBuildIds(List.of(manualBuildId))
                .stream()
                .map(ManualBuildItem::getProduct)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<Product> getAutoBuildProducts(int autoBuildId) {
        return autoBuildItemRepository.findWithProductByAutoBuildIds(List.of(autoBuildId))
                .stream()
                .map(AutoBuildItem::getProduct)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 회원의 장바구니 항목·빌드 부품·품목별 사용량을 일괄 조회해 메모리 스냅샷으로 구성합니다.
     */
    private CartSnapshot loadCartSnapshot(int userId) {
        List<CartItem> items = cartItemRepository.findWithDetailsByUserId(userId);
        if (items.isEmpty()) {
            return new CartSnapshot(List.of(), Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        }

        Set<Integer> manualBuildIds = new HashSet<>();
        Set<Integer> autoBuildIds = new HashSet<>();
        for (CartItem item : items) {
            if (item.getManualBuild() != null) manualBuildIds.add(item.getManualBuild().getManualBuildId());
            if (item.getAutoBuild() != null) autoBuildIds.add(item.getAutoBuild().getAutoBuildId());
        }

        Map<Integer, List<BuildPartEntry>> manualPartsById = loadManualPartsByBuildIds(manualBuildIds);
        Map<Integer, List<BuildPartEntry>> autoPartsById = loadAutoPartsByBuildIds(autoBuildIds);

        Map<Integer, Integer> usedByProduct = new HashMap<>();
        for (CartItem item : items) {
            int qty = item.getQuantity();
            if (item.getProduct() != null) {
                usedByProduct.merge(item.getProduct().getProductId(), qty, Integer::sum);
            } else {
                List<BuildPartEntry> entries = item.getManualBuild() != null
                        ? manualPartsById.getOrDefault(item.getManualBuild().getManualBuildId(), List.of())
                        : item.getAutoBuild() != null
                                ? autoPartsById.getOrDefault(item.getAutoBuild().getAutoBuildId(), List.of())
                                : List.of();
                Set<Integer> seenPartIds = new HashSet<>();
                for (BuildPartEntry entry : entries) {
                    Product part = entry.product();
                    if (part != null && seenPartIds.add(part.getProductId())) {
                        usedByProduct.merge(part.getProductId(), qty, Integer::sum);
                    }
                }
            }
        }

        return new CartSnapshot(items, manualPartsById, autoPartsById, usedByProduct);
    }

    private record BuildPartEntry(Product product, int unitPrice) {}

    private record CartSnapshot(
            List<CartItem> items,
            Map<Integer, List<BuildPartEntry>> manualPartsByBuildId,
            Map<Integer, List<BuildPartEntry>> autoPartsByBuildId,
            Map<Integer, Integer> usedQuantityByProductId
    ) {
        List<BuildPartEntry> partEntriesOf(CartItem item) {
            if (item.getManualBuild() != null) {
                return manualPartsByBuildId.getOrDefault(item.getManualBuild().getManualBuildId(), List.of());
            }
            if (item.getAutoBuild() != null) {
                return autoPartsByBuildId.getOrDefault(item.getAutoBuild().getAutoBuildId(), List.of());
            }
            return List.of();
        }

        List<Product> partsOf(CartItem item) {
            return partEntriesOf(item).stream().map(BuildPartEntry::product).filter(Objects::nonNull).toList();
        }

        int usedByOthers(int productId, CartItem excludeItem) {
            int total = usedQuantityByProductId.getOrDefault(productId, 0);
            if (excludeItem == null) return total;
            int selfUsed = 0;
            if (excludeItem.getProduct() != null && excludeItem.getProduct().getProductId() == productId) {
                selfUsed = excludeItem.getQuantity();
            } else if (excludeItem.getManualBuild() != null || excludeItem.getAutoBuild() != null) {
                if (partsOf(excludeItem).stream().anyMatch(p -> p.getProductId() == productId)) {
                    selfUsed = excludeItem.getQuantity();
                }
            }
            return Math.max(0, total - selfUsed);
        }

        int availableStock(Product product, CartItem excludeItem) {
            return Math.max(0, product.getStockQuantity() - usedByOthers(product.getProductId(), excludeItem));
        }

        int availableBuildStock(List<Product> parts, CartItem excludeItem) {
            return parts.stream()
                    .filter(Objects::nonNull)
                    .mapToInt(p -> availableStock(p, excludeItem))
                    .min()
                    .orElse(0);
        }
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