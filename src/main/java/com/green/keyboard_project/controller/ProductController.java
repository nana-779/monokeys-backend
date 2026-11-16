package com.green.keyboard_project.controller;

import com.green.keyboard_project.dto.ApiResponse;
import com.green.keyboard_project.dto.ProductResponse;
import com.green.keyboard_project.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/products")
@CrossOrigin(origins = {"http://localhost:3000", "http://127.0.0.1:3000"})
@RequiredArgsConstructor
public class ProductController {
    private final ProductService productService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ProductResponse>>> getProducts() {
        return ResponseEntity.ok(ApiResponse.ok(productService.getActiveProducts()));
    }

    @GetMapping("/{productId}")
    public ResponseEntity<ApiResponse<ProductResponse>> getProduct(@PathVariable int productId) {
        return ResponseEntity.ok(ApiResponse.ok(productService.getActiveProduct(productId)));
    }
}
