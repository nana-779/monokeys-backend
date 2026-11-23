package com.green.keyboard_project.dto.cart;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 장바구니 상품 추가 요청 DTO
 */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class CartAddRequest {
    private Integer productId;      // 일반 상품 ID (커스텀일 땐 null)
    private Integer manualBuildId;  // 수동 커스텀 빌드 ID (추가)
    private Integer autoBuildId;    // 자동 추천 빌드 ID (추가)
    private Integer quantity;       // 수량
}