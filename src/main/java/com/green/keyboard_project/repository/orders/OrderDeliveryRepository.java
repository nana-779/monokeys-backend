package com.green.keyboard_project.repository.orders;

import com.green.keyboard_project.entity.OrderDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderDeliveryRepository extends JpaRepository<OrderDelivery, Integer> {
    // 배송 정책이 확정될 때까지 배송 기록이 있는지만 조회합니다.
    boolean existsByOrder_OrderId(int orderId);
}
