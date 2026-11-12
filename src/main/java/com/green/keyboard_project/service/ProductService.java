package com.green.keyboard_project.service;

import com.green.keyboard_project.dto.CreateProductRequest;
import com.green.keyboard_project.entity.Category;
import com.green.keyboard_project.entity.Product;
import com.green.keyboard_project.entity.ProductSpec;
import com.green.keyboard_project.exceptions.ResourceNotFountException;
import com.green.keyboard_project.repository.CategoryRepository;
import com.green.keyboard_project.repository.ProductRepository;
import com.green.keyboard_project.repository.ProductSpecRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ProductService {
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductSpecRepository productSpecRepository;

    @Transactional
    public int createProduct(CreateProductRequest request) {
        Optional<Category> categoryOptional =
                categoryRepository.findByNameAndParentIsNull(request.getCategoryName().trim());

        if (categoryOptional.isEmpty()) {
            throw new ResourceNotFountException("최상위 카테고리를 찾을 수 없습니다.");
        }

        Category category = categoryOptional.get();
        LocalDateTime now = LocalDateTime.now();

        Product product = new Product();
        product.setCategory(category);
        product.setName(request.getName());
        product.setBasePrice(request.getBasePrice());
        product.setStockQuantity(request.getStockQuantity());
        product.setThumbnailUrl(request.getThumbnailUrl());
        product.setDescription(request.getDescription());
        product.setActive(true);
        product.setVersion(0);
        product.setCreatedAt(now);
        product.setUpdatedAt(now);

        Product savedProduct = productRepository.save(product);

        ProductSpec productSpec = new ProductSpec();
        productSpec.setProduct(savedProduct);
        productSpec.setLayout(request.getLayout());
        productSpec.setHousingMaterial(request.getHousingMaterial());
        productSpec.setSupportPins(request.getSupportPins());
        productSpec.setIsWireless(request.getIsWireless());
        productSpec.setSwitchPin(request.getSwitchPin());
        productSpec.setSwitchType(request.getSwitchType());
        productSpec.setKeycapMaterial(request.getKeycapMaterial());

        productSpecRepository.save(productSpec);

        return savedProduct.getProductId();
    }
}
