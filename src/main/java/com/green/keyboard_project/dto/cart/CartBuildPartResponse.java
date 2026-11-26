package com.green.keyboard_project.dto.cart;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 장바구니 커스텀 빌드 구성품 상세 DTO
 */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Builder
public class CartBuildPartResponse {
    private Integer productId;
    private String partType;
    private String name;
    private Integer unitPrice;
}
