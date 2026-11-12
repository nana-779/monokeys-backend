package com.green.keyboard_project.service;

import com.green.keyboard_project.dto.product.ProductListResponseDto;
import com.green.keyboard_project.dto.product.ProductResponseDto;
import com.green.keyboard_project.entity.Product;
import com.green.keyboard_project.entity.ProductSpec;
import com.green.keyboard_project.exceptions.ResourceNotFountException;
import com.green.keyboard_project.repository.ProductRepository;
import com.green.keyboard_project.repository.ProductSpecRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductSpecRepository productSpecRepository;

    public ProductListResponseDto getProducts(String category, String subcategory, int page, int size) {
        // subcategory 호환 처리 (housing-abs -> housing-plastic)
        if ("housing-abs".equalsIgnoreCase(subcategory)) {
            subcategory = "housing-plastic";
        }

        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, size), Sort.by(Sort.Direction.ASC, "productId"));
        Page<Product> productPage = productRepository.findFilteredProducts(
                (category != null && !category.isBlank()) ? category.trim() : null,
                (subcategory != null && !subcategory.isBlank()) ? subcategory.trim() : null,
                pageable
        );

        List<ProductResponseDto> content = productPage.getContent().stream()
                .map(this::toDto)
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
                .orElseThrow(() -> new ResourceNotFountException("상품을 찾을 수 없습니다. (ID: " + productId + ")"));
        return toDto(product);
    }

    private ProductResponseDto toDto(Product p) {
        ProductSpec spec = productSpecRepository.findById(p.getProductId()).orElse(null);

        String parentId = "";
        if (p.getCategory() != null) {
            if (p.getCategory().getParent() != null) {
                parentId = p.getCategory().getParent().getCategoryId();
            } else {
                parentId = p.getCategory().getCategoryId();
            }
        }

        String normCategory = "keyboards";
        if (parentId.contains("hous")) normCategory = "housing";
        else if (parentId.contains("switch")) normCategory = "switches";
        else if (parentId.contains("keycap")) normCategory = "keycaps";

        boolean isSoldOut = !p.isActive() || p.getStockQuantity() <= 0;
        String thumb = p.getThumbnailUrl() != null ? p.getThumbnailUrl() : "";

        List<ProductResponseDto.MediaDto> images = new ArrayList<>();
        images.add(ProductResponseDto.MediaDto.builder()
                .id(p.getProductId() + "-0")
                .url(thumb)
                .alt(p.getName())
                .build());

        List<String> gallery = List.of(thumb);

        String layout = null;
        String material = null;
        String switchType = null;
        Boolean isWireless = null;
        Integer pin = null;

        if (spec != null) {
            layout = spec.getLayout();
            if (spec.getHousingMaterial() != null) material = spec.getHousingMaterial();
            else if (spec.getKeycapMaterial() != null) material = spec.getKeycapMaterial();
            switchType = spec.getSwitchType();
            isWireless = spec.getIsWireless();
            pin = spec.getSwitchPin();
        }

        return ProductResponseDto.builder()
                .id(String.valueOf(p.getProductId()))
                .name(p.getName())
                .slug(String.valueOf(p.getProductId()))
                .basePrice(p.getBasePrice())
                .category(normCategory)
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
                .images(images)
                .galleryImages(gallery)
                .build();
    }
}
