package com.green.keyboard_project.dto.cart;

import com.green.keyboard_project.entity.AutoBuild;
import com.green.keyboard_project.entity.CartItem;
import com.green.keyboard_project.entity.ManualBuild;
import com.green.keyboard_project.entity.Product;

import java.util.List;

/**
 * 주문 생성 시 장바구니 항목 1건을 서버 기준으로 검증·해석한 결과입니다.
 * - 일반/커스텀 키캡은 product + [product]를, 수동/자동 빌드는 해당 빌드 + 구성 부품 목록을 제공합니다.
 */
public record CartOrderLine(
        int cartItemId,
        CartItem cartItem,
        Product product,
        ManualBuild manualBuild,
        AutoBuild autoBuild,
        String customDesignData,
        String itemNameSnapshot,
        int unitPrice,
        int quantity,
        List<Product> stockTargets
) {}
