package com.green.keyboard_project.service;

import com.green.keyboard_project.dto.CreateProductRequest;
import com.green.keyboard_project.dto.ProductResponse;
import com.green.keyboard_project.dto.product.ProductListResponseDto;
import com.green.keyboard_project.dto.product.ProductResponseDto;
import com.green.keyboard_project.entity.Category;
import com.green.keyboard_project.entity.Product;
import com.green.keyboard_project.entity.ProductImage;
import com.green.keyboard_project.entity.ProductSpec;
import com.green.keyboard_project.exceptions.ResourceNotFoundException;
import com.green.keyboard_project.repository.CategoryRepository;
import com.green.keyboard_project.repository.ProductImageRepository;
import com.green.keyboard_project.repository.ProductRepository;
import com.green.keyboard_project.repository.ProductSpecRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductSpecRepository productSpecRepository;
    private final ProductImageRepository productImageRepository;

    // ==================== [스토어프론트 조회 API - feature/products] ====================

    public ProductListResponseDto getProducts(String category, String subcategory, int page, int size) {
        return getProducts(category, subcategory, null, null, page, size);
    }

    public ProductListResponseDto getProducts(
            String category,
            String subcategory,
            String keyword,
            String sort,
            int page,
            int size
    ) {
        // category 호환 처리 (keyboards -> pcb)
        if ("keyboards".equalsIgnoreCase(category)) {
            category = "pcb";
        }
        // subcategory 호환 처리 (housing-abs -> housing-plastic)
        if ("housing-abs".equalsIgnoreCase(subcategory)) {
            subcategory = "housing-plastic";
        }

        String normalizedKeyword = normalizeSearchKeyword(keyword);
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.max(1, size),
                resolveProductSort(sort)
        );
        Page<Product> productPage = productRepository.findFilteredProducts(
                (category != null && !category.isBlank()) ? category.trim() : null,
                (subcategory != null && !subcategory.isBlank()) ? subcategory.trim() : null,
                normalizedKeyword,
                pageable
        );

        List<Product> products = productPage.getContent();
        List<Integer> productIds = products.stream().map(Product::getProductId).collect(Collectors.toList());
        Map<Integer, List<ProductImage>> imagesByProductId = productIds.isEmpty()
                ? Collections.emptyMap()
                : productImageRepository.findByProduct_ProductIdInOrderByDisplayOrderAsc(productIds)
                        .stream()
                        .collect(Collectors.groupingBy(img -> img.getProduct().getProductId()));
        Map<Integer, ProductSpec> specsByProductId = productIds.isEmpty()
                ? Collections.emptyMap()
                : productSpecRepository.findAllById(productIds).stream()
                        .collect(Collectors.toMap(ProductSpec::getProductId, spec -> spec));

        List<ProductResponseDto> content = products.stream()
                .map(p -> toDto(
                        p,
                        imagesByProductId.getOrDefault(p.getProductId(), Collections.emptyList()),
                        specsByProductId.get(p.getProductId())
                ))
                .collect(Collectors.toList());

        return ProductListResponseDto.builder()
                .content(content)
                .pageNumber(productPage.getNumber())
                .pageSize(productPage.getSize())
                .totalElements(productPage.getTotalElements())
                .totalPages(productPage.getTotalPages())
                .build();
    }

    public ProductResponseDto getProductById(int productId) {
        Product product = productRepository.findById(productId)
                .filter(p -> !p.isDeleted() && p.isActive() && p.getStockQuantity() > 0)
                .orElseThrow(() -> new ResourceNotFoundException("상품을 찾을 수 없습니다. (ID: " + productId + ")"));
        List<ProductImage> galleryEntities = productImageRepository.findByProduct_ProductIdOrderByDisplayOrderAsc(productId);
        ProductSpec spec = productSpecRepository.findById(productId).orElse(null);
        return toDto(product, galleryEntities, spec);
    }

    private ProductResponseDto toDto(Product p, List<ProductImage> galleryEntities, ProductSpec spec) {

        String parentId = "";
        if (p.getCategory() != null) {
            if (p.getCategory().getParent() != null) {
                parentId = p.getCategory().getParent().getCategoryId();
            } else {
                parentId = p.getCategory().getCategoryId();
            }
        }

        String normCategory = "pcb";
        if (parentId.contains("hous")) normCategory = "housing";
        else if (parentId.contains("switch")) normCategory = "switches";
        else if (parentId.contains("keycap")) normCategory = "keycaps";

        boolean isSoldOut = !p.isActive() || p.getStockQuantity() <= 0;
        String thumb = p.getThumbnailUrl() != null ? p.getThumbnailUrl() : "";

        List<ProductResponseDto.MediaDto> images = new ArrayList<>();
        List<String> gallery = new ArrayList<>();

        if (galleryEntities != null && !galleryEntities.isEmpty()) {
            for (int i = 0; i < galleryEntities.size(); i++) {
                String url = galleryEntities.get(i).getImageUrl();
                images.add(ProductResponseDto.MediaDto.builder()
                        .id(p.getProductId() + "-" + i)
                        .url(url)
                        .alt(p.getName() + " 이미지 " + (i + 1))
                        .build());
                gallery.add(url);
            }
        } else if (!thumb.isBlank()) {
            images.add(ProductResponseDto.MediaDto.builder()
                    .id(p.getProductId() + "-0")
                    .url(thumb)
                    .alt(p.getName())
                    .build());
            gallery.add(thumb);
        }

        String layout = null;
        String material = null;
        String switchType = null;
        Boolean isWireless = null;
        Integer pin = null;
        List<Integer> supportPins = Collections.emptyList();

        if (spec != null) {
            layout = spec.getLayout();
            if (spec.getHousingMaterial() != null) material = spec.getHousingMaterial();
            else if (spec.getKeycapMaterial() != null) material = spec.getKeycapMaterial();
            switchType = spec.getSwitchType();
            isWireless = spec.getIsWireless();
            pin = spec.getSwitchPin();
            supportPins = parseSupportPins(spec.getSupportPins());
        }

        return ProductResponseDto.builder()
                .id(String.valueOf(p.getProductId()))
                .name(p.getName())
                .slug(String.valueOf(p.getProductId()))
                .basePrice(p.getBasePrice())
                .category(normCategory)
                .partType(p.getDerivedPartType())
                .categoryId(p.getCategory() != null ? p.getCategory().getCategoryId() : null)
                .parentCategoryId(parentId)
                .subCategoryName(p.getCategory() != null ? p.getCategory().getName() : null)
                .description(p.getDescription())
                .stockQuantity(p.getStockQuantity())
                .shippingFee(0)
                .brand("MONO / STUDIO")
                .isSoldOut(isSoldOut)
                .status(isSoldOut ? "SOLD_OUT" : "ACTIVE")
                .layout(layout)
                .material(material)
                .switchType(switchType)
                .isWireless(isWireless)
                .pin(pin)
                .supportPins(supportPins)
                .images(images)
                .galleryImages(gallery)
                .build();
    }

    public static List<Integer> parseSupportPins(String rawPins) {
        if (rawPins == null || rawPins.isBlank()) {
            return Collections.emptyList();
        }
        List<Integer> pins = new ArrayList<>();
        if (rawPins.contains("3")) pins.add(3);
        if (rawPins.contains("5")) pins.add(5);
        return pins;
    }

    private String normalizeSearchKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String trimmed = keyword.trim();
        if (trimmed.length() > 100) {
            trimmed = trimmed.substring(0, 100);
        }
        return trimmed
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }

    private Sort resolveProductSort(String sort) {
        if (sort == null) {
            return Sort.by(Sort.Direction.ASC, "productId");
        }
        return switch (sort.trim().toLowerCase()) {
            case "price_asc" -> Sort.by(Sort.Order.asc("basePrice"), Sort.Order.asc("productId"));
            case "price_desc" -> Sort.by(Sort.Order.desc("basePrice"), Sort.Order.asc("productId"));
            case "latest" -> Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("productId"));
            case "name_asc" -> Sort.by(Sort.Order.asc("name"), Sort.Order.asc("productId"));
            default -> Sort.by(Sort.Direction.ASC, "productId");
        };
    }

    /**
     * 판매·재고가 있는 실제 카테고리, 찜(wishlist), 주문(order_item) 빈도를 조합해
     * 대분류별로 고르게 동적 추천 검색어를 반환합니다.
     */
    public List<String> getSearchSuggestions(int limit) {
        int safeLimit = Math.min(10, Math.max(1, limit));
        List<Object[]> metrics = productRepository.findActiveSubcategoryMetrics();
        if (metrics.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Long> orderByCategory = new HashMap<>();
        for (Object[] row : productRepository.findOrderQuantityByActiveCategory()) {
            orderByCategory.put((String) row[0], ((Number) row[1]).longValue());
        }

        Map<String, Long> wishlistByCategory = new HashMap<>();
        for (Object[] row : productRepository.findWishlistCountByActiveCategory()) {
            wishlistByCategory.put((String) row[0], ((Number) row[1]).longValue());
        }

        record RankedSubcategory(String name, String parentId, long engagement, long score, long productCount) {}

        List<RankedSubcategory> ranked = metrics.stream()
                .map(row -> {
                    String catId = (String) row[0];
                    String catName = (String) row[1];
                    String parentId = (String) row[2];
                    long productCount = ((Number) row[3]).longValue();
                    long orderCount = orderByCategory.getOrDefault(catId, 0L);
                    long wishlistCount = wishlistByCategory.getOrDefault(catId, 0L);
                    long engagement = orderCount * 3L + wishlistCount * 2L;
                    long score = engagement + productCount;
                    return new RankedSubcategory(catName, parentId, engagement, score, productCount);
                })
                .sorted((a, b) -> {
                    int byScore = Long.compare(b.score(), a.score());
                    return byScore != 0 ? byScore : Long.compare(b.productCount(), a.productCount());
                })
                .toList();

        List<String> result = new ArrayList<>();
        // 실제 주문/찜 반응이 있는 최상위 카테고리는 1순위로 고정 노출하고 나머지는 매 요청 셔플
        RankedSubcategory topEngaged = ranked.stream()
                .filter(item -> item.engagement() > 0)
                .findFirst()
                .orElse(null);
        if (topEngaged != null) {
            result.add(topEngaged.name());
        }

        // 대분류별로 활성 후보를 모은 뒤 요청마다 버킷 내부 & 대분류 순서를 섞어 새로고침 시마다 다른 조합을 제공
        Map<String, List<RankedSubcategory>> buckets = new LinkedHashMap<>();
        for (RankedSubcategory item : ranked) {
            if (topEngaged != null && item.name().equals(topEngaged.name())) continue;
            buckets.computeIfAbsent(item.parentId(), k -> new ArrayList<>()).add(item);
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<List<RankedSubcategory>> bucketList = new ArrayList<>(buckets.values());
        for (List<RankedSubcategory> bucket : bucketList) {
            Collections.shuffle(bucket, random);
        }
        Collections.shuffle(bucketList, random);

        int round = 0;
        while (result.size() < safeLimit) {
            boolean addedInRound = false;
            for (List<RankedSubcategory> bucket : bucketList) {
                if (round < bucket.size()) {
                    String name = bucket.get(round).name();
                    if (!result.contains(name)) {
                        result.add(name);
                        if (result.size() >= safeLimit) break;
                    }
                    addedInRound = true;
                }
            }
            if (!addedInRound) break;
            round++;
        }
        return result;
    }
}
