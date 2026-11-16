package com.green.keyboard_project.controller;

import com.green.keyboard_project.dto.ApiResponse;
import com.green.keyboard_project.dto.CreateProductRequest;
import com.green.keyboard_project.service.ProductService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import com.green.keyboard_project.service.ProductImageStorageService;
import com.green.keyboard_project.dto.ProductResponse;

@RestController
@RequestMapping("/api/admin/products")
@CrossOrigin(origins = {"http://localhost:3000", "http://127.0.0.1:3000"})
@AllArgsConstructor
public class AdminProductController {
    private final ProductService productService;
    private final ProductImageStorageService imageStorageService;

    // 상품 등록
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ProductResponse>> createProduct(
            @Valid @RequestPart("product") CreateProductRequest request,
            @RequestPart(value = "image", required = false) MultipartFile image
    ) {
        String imageUrl = image != null && !image.isEmpty() ? imageStorageService.store(image) : null;
        try {
            ProductResponse created = productService.createProduct(request, imageUrl);
            URI location = URI.create("/admin/products/" + created.productId());

            return ResponseEntity.created(location).body(ApiResponse.ok(created));
        } catch (RuntimeException e) {
            imageStorageService.deleteByUrl(imageUrl);
            throw e;
        }
    }

    @PatchMapping(value = "/{productId}/edit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ProductResponse>> updateProduct(
            @PathVariable int productId,
            @Valid @RequestPart("product") CreateProductRequest request,
            @RequestPart(value = "image", required = false) MultipartFile image
    ) {
        String newImageUrl = image != null && !image.isEmpty() ? imageStorageService.store(image) : null;
        String oldImageUrl = null;
        ProductResponse updated;
        try {
            if (newImageUrl != null) oldImageUrl = productService.getThumbnailUrl(productId);
            updated = productService.updateProduct(productId, request, newImageUrl);
        } catch (RuntimeException e) {
            imageStorageService.deleteByUrl(newImageUrl);
            throw e;
        }
        if (newImageUrl != null) imageStorageService.deleteByUrl(oldImageUrl);
        return ResponseEntity.ok(ApiResponse.ok(updated));
    }

    @DeleteMapping("/{productId}")
    public ResponseEntity<ApiResponse<Void>> deleteProduct(@PathVariable int productId) {
        productService.deleteProduct(productId);
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
