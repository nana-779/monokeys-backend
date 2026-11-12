package com.green.keyboard_project.controller;

import com.green.keyboard_project.dto.ApiResponse;
import com.green.keyboard_project.dto.CreateProductRequest;
import com.green.keyboard_project.service.ProductService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/admin/products")
@AllArgsConstructor
public class AdminProductController {
    private final ProductService productService;

    // 상품 등록
    @PostMapping
    public ResponseEntity<ApiResponse<Void>> createProduct(
            @Valid @RequestBody CreateProductRequest request
    ) {
        int newProductId = productService.createProduct(request);
        URI location = URI.create("/admin/products/" + newProductId);

        return ResponseEntity
                .created(location)
                .body(ApiResponse.ok());
    }
}
