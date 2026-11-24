package com.green.keyboard_project.repository.orders;

import com.green.keyboard_project.dto.order.MyOrderSummary;
import com.green.keyboard_project.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    // 로그인 회원의 주문만 찾고, 결제 기록이 없는 주문도 목록에 남깁니다.
    // 결제 전에는 주문 확정 시각이 없으므로 주문을 생성한 시각을 사용합니다.
    @Query("""
            select new com.green.keyboard_project.dto.order.MyOrderSummary$Header(
                o.orderId, o.orderNumber, coalesce(o.orderedAt, o.createdAt), p.paidAmount,
                o.orderStatus, p.paymentStatus, d.deliveryStatus
            )
            from Order o
            join o.user u
            left join Payment p on p.order = o
            left join OrderDelivery d on d.order = o
            where u.userId = :userId and u.isDeleted = false
                and (:orderNumber is null or o.orderNumber = :orderNumber)
            order by coalesce(o.orderedAt, o.createdAt) desc, o.orderId desc
            """)
    List<MyOrderSummary.Header> findMyOrderSummaries(@Param("userId") int userId,
                                                   @Param("orderNumber") String orderNumber);

    // 전체 주문 목록 최신 등록일시 내림차순 조회
    List<Order> findAllByOrderByCreatedAtDesc();
}
