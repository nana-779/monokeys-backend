package com.green.keyboard_project.controller;

import com.green.keyboard_project.dto.ApiResponse;
import com.green.keyboard_project.dto.CreateProductRequest;
import com.green.keyboard_project.service.AdminProductService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import com.green.keyboard_project.service.ProductImageStorageService;
import com.green.keyboard_project.dto.ProductResponse;

@RestController
@RequestMapping("/api/admin/products")
@CrossOrigin(origins = {"http://localhost:3000", "http://127.0.0.1:3000"})
@AllArgsConstructor
public class AdminProductController {
    private final AdminProductService adminProductService;
    private final ProductImageStorageService imageStorageService;

    // 관리자 전체 상품 조회
    @GetMapping
    public ResponseEntity<ApiResponse<List<ProductResponse>>> getAllProduct() {
        return ResponseEntity.ok(ApiResponse.ok(adminProductService.getAllProducts()));
    }

    // 관리자 상세 상품 조회
    @GetMapping("/{productId}")
    public ResponseEntity<ApiResponse<ProductResponse>> getProduct(@PathVariable int productId) {
        return ResponseEntity.ok(ApiResponse.ok(adminProductService.getProduct(productId)));
    }

    // 상품 등록
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ProductResponse>> createProduct(
            @Valid @RequestPart("product") CreateProductRequest request,
            @RequestPart(value = "image", required = false) MultipartFile image
    ) {
        String imageUrl = image != null && !image.isEmpty() ? imageStorageService.store(image) : null;
        try {
            ProductResponse created = adminProductService.createProduct(request, imageUrl);
            URI location = URI.create("/api/admin/products/" + created.productId());

            return ResponseEntity.created(location).body(ApiResponse.ok(created));
        } catch (RuntimeException e) {
            imageStorageService.deleteByUrl(imageUrl);
            throw e;
        }
    }

    // 상품 수정
    @PatchMapping("/{productId}")
    public ResponseEntity<ApiResponse<Void>> updateProduct() {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(ApiResponse.fail("상품 수정 기능은 현재 준비 중입니다."));
    }

    // 상품 삭제
    @DeleteMapping("/{productId}")
    public ResponseEntity<ApiResponse<Void>> deleteProduct(@PathVariable int productId) {
        adminProductService.deleteProduct(productId);
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
