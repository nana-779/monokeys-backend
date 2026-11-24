package com.green.keyboard_project.repository.orders;

import com.green.keyboard_project.entity.Order;
import com.green.keyboard_project.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 주문 상품(OrderItem) 데이터 접근 리포지토리
 */
@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, Integer> {

    // 특정 주문(Order)에 속한 주문 상품 목록 조회
    List<OrderItem> findByOrder(Order order);

    // 본인 주문 조회에서 확인된 ID의 품목과 썸네일을 일괄 조회합니다.
    @Query("""
            select item from OrderItem item
            join fetch item.order o
            left join fetch item.product
            where o.orderId in :orderIds
            order by item.orderItemId
            """)
    List<OrderItem> findMyOrderItems(@Param("orderIds") List<Integer> orderIds);

    boolean existsByManualBuild_ManualBuildId(int manualBuildId);

    boolean existsByAutoBuild_AutoBuildId(int autoBuildId);
}
