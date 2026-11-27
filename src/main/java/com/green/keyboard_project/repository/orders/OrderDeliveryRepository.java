package com.green.keyboard_project.repository.orders;

import com.green.keyboard_project.entity.OrderDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import com.green.keyboard_project.entity.OrderDelivery.DeliveryStatusType;

@Repository
public interface OrderDeliveryRepository extends JpaRepository<OrderDelivery, Integer> {
    java.util.Optional<OrderDelivery> findByOrder_OrderId(int orderId);

    boolean existsByOrder_OrderIdAndDeliveryStatusNot(int orderId, DeliveryStatusType status);

    @Modifying
    @Query(value = """
            UPDATE order_delivery SET delivery_status = 'DELIVERED',
                delivered_at = COALESCE(delivered_at, CURRENT_TIMESTAMP), receipt_confirmed_at = CURRENT_TIMESTAMP
            WHERE delivery_status IN ('SHIPPING','DELIVERED') AND receipt_confirmed_at IS NULL AND order_id IN
                (SELECT order_id FROM orders WHERE order_number = :orderNumber AND user_id = :userId
                 AND order_status IN ('ORDERED','CONFIRMED'))
            """, nativeQuery = true)
    int confirmMyDelivery(String orderNumber, int userId);

    @Modifying
    @Query(value = """
            UPDATE order_delivery SET delivery_status = 'DELIVERED', delivered_at = CURRENT_TIMESTAMP
            WHERE delivery_status = 'SHIPPING' AND shipped_at <= TIMESTAMPADD(DAY, -14, CURRENT_TIMESTAMP)
              AND order_id IN (SELECT order_id FROM orders WHERE order_status IN ('ORDERED','CONFIRMED'))
            """, nativeQuery = true)
    int completeOverdueDeliveries();
}
