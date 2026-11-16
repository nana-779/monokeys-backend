package com.green.keyboard_project.service;

import com.green.keyboard_project.dto.CreateProductRequest;
import com.green.keyboard_project.dto.ProductResponse;
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
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductSpecRepository productSpecRepository;

    @Transactional
    public ProductResponse createProduct(CreateProductRequest request, String imageUrl) {
        Category category = findRootCategory(request.getCategoryName());
        LocalDateTime now = LocalDateTime.now();

        Product product = new Product();
        product.setCategory(category);
        product.setName(request.getName());
        product.setBasePrice(request.getBasePrice());
        product.setStockQuantity(request.getStockQuantity());
        product.setThumbnailUrl(imageUrl);
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

        return ProductResponse.from(savedProduct, productSpec);
    }

    @Transactional
    public ProductResponse updateProduct(int productId, CreateProductRequest request, String newImageUrl) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFountException("상품을 찾을 수 없습니다."));
        Category category = findRootCategory(request.getCategoryName());
        product.setCategory(category);
        product.setName(request.getName());
        product.setBasePrice(request.getBasePrice());
        product.setStockQuantity(request.getStockQuantity());
        product.setDescription(request.getDescription());
        if (newImageUrl != null) product.setThumbnailUrl(newImageUrl);
        product.setUpdatedAt(LocalDateTime.now());
        ProductSpec spec = productSpecRepository.findById(productId).orElseGet(() -> {
            ProductSpec created = new ProductSpec();
            created.setProduct(product);
            return created;
        });
        spec.setLayout(request.getLayout());
        spec.setHousingMaterial(request.getHousingMaterial());
        spec.setSupportPins(request.getSupportPins());
        spec.setIsWireless(request.getIsWireless());
        spec.setSwitchPin(request.getSwitchPin());
        spec.setSwitchType(request.getSwitchType());
        spec.setKeycapMaterial(request.getKeycapMaterial());
        productSpecRepository.save(spec);
        Product saved = productRepository.save(product);
        return ProductResponse.from(saved, spec);
    }

    @Transactional(readOnly = true)
    public String getThumbnailUrl(int productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFountException("상품을 찾을 수 없습니다."))
                .getThumbnailUrl();
    }

    @Transactional
    public void deleteProduct(int productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFountException("상품을 찾을 수 없습니다."));
        product.setActive(false);
        product.setUpdatedAt(LocalDateTime.now());
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> getActiveProducts() {
        return productRepository.findAll().stream().filter(Product::isActive)
                .map(product -> ProductResponse.from(product, productSpecRepository.findById(product.getProductId()).orElse(null)))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProductResponse getActiveProduct(int productId) {
        Product product = productRepository.findById(productId)
                .filter(Product::isActive)
                .orElseThrow(() -> new ResourceNotFountException("상품을 찾을 수 없습니다."));
        return ProductResponse.from(product, productSpecRepository.findById(productId).orElse(null));
    }

    private Category findRootCategory(String categoryName) {
        String categoryId = switch (categoryName.trim()) {
            case "하우징" -> "housing";
            case "기판", "기판 (PCB)" -> "pcb";
            case "스위치" -> "switches";
            case "키캡" -> "keycaps";
            default -> throw new ResourceNotFountException("카테고리를 찾을 수 없습니다.");
        };

        return categoryRepository.findById(categoryId)
                .filter(category -> category.getParent() == null)
                .orElseThrow(() -> new ResourceNotFountException("카테고리를 찾을 수 없습니다."));
    }
}
