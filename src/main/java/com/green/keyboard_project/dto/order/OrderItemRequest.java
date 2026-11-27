package com.green.keyboard_project.dto.order;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 주문 품목 요청 DTO
 * - 주문 생성 시 개별 상품의 ID와 주문 수량을 담는 모델입니다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItemRequest {
    // 장바구니 항목 ID (장바구니 기반 주문 시 필수)
    private Integer cartItemId;

    // 단품 상품 식별자 (선택)
    private Integer productId;

    // 커스텀 빌드 식별자 (선택)
    private Integer manualBuildId;
    private Integer autoBuildId;

    // 커스텀 디자인 데이터 (선택)
    private String customDesignData;

    // 주문 수량 (최소 1개 이상)
    @NotNull(message = "수량은 필수입니다.")
    @Min(value = 1, message = "수량은 1개 이상이어야 합니다.")
    private Integer quantity;
}
