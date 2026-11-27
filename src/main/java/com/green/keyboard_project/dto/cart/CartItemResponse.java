package com.green.keyboard_project.dto.cart;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 장바구니 아이템 응답 DTO (1파일 1클래스)
 */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
public class CartItemResponse {
    private Integer cartItemId;
    private Integer userId;
    private Integer productId;
    private Integer manualBuildId;  // 수동 빌드 식별용
    private Integer autoBuildId;    // 자동 빌드 식별용
    private String buildType;       // "MANUAL", "AUTO", 일반 상품은 null
    private String name;
    private Integer price;
    private Integer stockQuantity;
    private Integer quantity;
    private String thumbnailUrl;
    private String customDesignData;
    private Boolean includeAssembly;
    private List<Integer> partProductIds;
    private List<CartBuildPartResponse> partDetails;
}