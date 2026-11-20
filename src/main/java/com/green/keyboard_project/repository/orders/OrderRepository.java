package com.green.keyboard_project.repository.orders;

import com.green.keyboard_project.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 주문(Order) 데이터 접근 리포지토리
 */
@Repository
public interface OrderRepository extends JpaRepository<Order, Integer> {

    // 고유 주문번호(ORD-...) 기준 단건 조회
    Optional<Order> findByOrderNumber(String orderNumber);

    // 사전 조회에서는 탈퇴하지 않은 로그인 회원의 주문만 찾습니다.
    Optional<Order> findByOrderNumberAndUser_UserIdAndUser_IsDeletedFalse(String orderNumber, int userId);

    // 전체 주문 목록 최신 등록일시 내림차순 조회
    List<Order> findAllByOrderByCreatedAtDesc();
}
