package com.green.keyboard_project.service;

import com.green.keyboard_project.dto.CreateProductRequest;
import com.green.keyboard_project.dto.ProductResponse;
import com.green.keyboard_project.entity.Category;
import com.green.keyboard_project.entity.Product;
import com.green.keyboard_project.entity.ProductSpec;
import com.green.keyboard_project.exceptions.ResourceNotFoundException;
import com.green.keyboard_project.repository.CategoryRepository;
import com.green.keyboard_project.repository.ProductRepository;
import com.green.keyboard_project.repository.ProductSpecRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductSpecRepository productSpecRepository;

    // 관리자 상품 등록
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

    // 관리자 전체 상품 조회
    @Transactional(readOnly = true)
    public List<ProductResponse> getAllProducts() {
        return productRepository.findAll().stream()
                .map(product -> ProductResponse.from(
                        product,
                        productSpecRepository.findById(product.getProductId()).orElse(null)))
                .toList();
    }

    // 관리자 상세 상품 조회
    @Transactional(readOnly = true)
    public ProductResponse getProduct(int productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("상품을 찾을 수 없습니다"));
        ProductSpec spec = productSpecRepository.findById(productId).orElse(null);
        return ProductResponse.from(product, spec);
    }

    @Transactional
    public void deleteProduct(int productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("상품을 찾을 수 없습니다"));
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
                .orElseThrow(() -> new ResourceNotFoundException("상품을 찾을 수 없습니다."));
        return ProductResponse.from(product, productSpecRepository.findById(productId).orElse(null));
    }

    private Category findRootCategory(String categoryName) {
        String categoryId = switch (categoryName.trim()) {
            case "하우징" -> "housing";
            case "기판", "기판 (PCB)" -> "pcb";
            case "스위치" -> "switches";
            case "키캡" -> "keycaps";
            default -> throw new ResourceNotFoundException("카테고리를 찾을 수 없습니다.");
        };

        return categoryRepository.findById(categoryId)
                .filter(category -> category.getParent() == null)
                .orElseThrow(() -> new ResourceNotFoundException("카테고리를 찾을 수 없습니다."));
    }

}
