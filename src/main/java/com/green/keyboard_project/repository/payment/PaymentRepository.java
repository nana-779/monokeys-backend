package com.green.keyboard_project.repository.payment;

import com.green.keyboard_project.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Integer> {
    // 주문에 연결된 결제 한 건을 사전 조회합니다.
    Optional<Payment> findByOrder_OrderId(int orderId);
}
