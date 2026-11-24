package com.green.keyboard_project.service;

import com.green.keyboard_project.exceptions.InvalidStateException;
import lombok.RequiredArgsConstructor;
import com.green.keyboard_project.dto.order.admin.AdminOrder;
import com.green.keyboard_project.dto.order.admin.AdminOrderEdit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminOrderService {
    private final JdbcTemplate jdbc;

    // 관리자 주문 조회
    public List<AdminOrder> getAdminOrders() {
        return jdbc.query("""
                SELECT o.order_id, o.order_number, p.paid_at, p.paid_amount, o.order_status, p.payment_status,
                       d.delivery_status, d.tracking_company, d.tracking_number, o.orderer_name,
                       o.orderer_phone, o.postal_code, o.road_address, o.detail_address,
                       o.delivery_memo, i.product_name_snapshot, i.quantity
                FROM orders o LEFT JOIN payment p ON p.order_id = o.order_id
                LEFT JOIN order_delivery d ON d.order_id = o.order_id
                LEFT JOIN order_item i ON i.order_id = o.order_id
                ORDER BY CASE
                    WHEN o.order_status = 'CANCELLED' OR p.payment_status = 'CANCELLED' THEN 4
                    WHEN d.delivery_status = 'PREPARING' THEN 1
                    WHEN d.delivery_status = 'SHIPPING' THEN 2
                    WHEN d.delivery_status = 'DELIVERED' THEN 3
                    ELSE 5 END, o.created_at DESC, o.order_id DESC, i.order_item_id
                """, rs -> {
            List<AdminOrder> orders = new ArrayList<>();
            AdminOrder order = null;
            // SQL에서 같은 주문의 상품은 연속해서 나오므로 주문이 바뀔 때만 행을 추가합니다.
            while (rs.next()) {
                if (order == null || order.id() != rs.getInt("order_id")) {
                    order = readAdminOrder(rs);
                    orders.add(order);
                }
                String productName = rs.getString("product_name_snapshot");
                if (productName != null) {
                    order.items().add(new AdminOrder.Item(productName, rs.getInt("quantity")));
                }
            }
            return orders;
        });
    }

    // 주문 한 건당 객체 만들기
    private AdminOrder readAdminOrder(ResultSet rs) throws SQLException {
        return new AdminOrder(
                rs.getInt("order_id"), rs.getString("order_number"),
                rs.getObject("paid_at", LocalDateTime.class), rs.getObject("paid_amount", Integer.class),
                rs.getString("order_status"), rs.getString("payment_status"), rs.getString("delivery_status"),
                rs.getString("tracking_company"), rs.getString("tracking_number"),
                rs.getString("orderer_name"), rs.getString("orderer_phone"),
                rs.getString("postal_code"), rs.getString("road_address"), rs.getString("detail_address"), rs.getString("delivery_memo"), new ArrayList<>());
    }

    // 관리자 배송 내역 수정 (주문 내역 업데이트)
    @Transactional
    public void updateAdminOrder(int orderId, AdminOrderEdit edit) {
        if (edit.postalCode() != null || edit.roadAddress() != null || edit.detailAddress() != null) {
            var editable = jdbc.queryForList("""
                    SELECT d.delivery_id FROM order_delivery d JOIN orders o ON o.order_id = d.order_id
                    WHERE d.order_id = ? AND d.delivery_status = 'PREPARING' AND o.order_status = 'ORDERED'
                    FOR UPDATE
                    """, Integer.class, orderId);
            if (editable.isEmpty()) throw new InvalidStateException("배송 준비 중인 주문만 배송지를 수정할 수 있습니다.");
        }
        int updated = jdbc.update("""
                UPDATE orders SET orderer_name = COALESCE(?, orderer_name),
                    orderer_phone = COALESCE(?, orderer_phone), postal_code = COALESCE(?, postal_code),
                    road_address = COALESCE(?, road_address), detail_address = COALESCE(?, detail_address)
                WHERE order_id = ?
                """, edit.name(), edit.phone(), edit.postalCode(), edit.roadAddress(), edit.detailAddress(), orderId);
        if (updated == 0 && !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM orders WHERE order_id = ?)", Boolean.class, orderId))) {
            throw new InvalidStateException("주문을 찾을 수 없습니다.");
        }
        if (edit.company() != null || edit.tracking() != null) {
            if (edit.company() == null || edit.tracking() == null) {
                throw new InvalidStateException("배송사와 트래킹 넘버를 함께 입력하세요.");
            }
            saveShipment(orderId, edit.company(), edit.tracking(), true);
        }
    }

    // 송장 처음 등록
    @Transactional
    public void registerAdminShipment(int orderId, String company, String tracking) {
        saveShipment(orderId, company, tracking, false);
    }

    // 배송사, 송장 수정
    // 첫 등록이면 발송 처리, 수정이면 기존 배송 상태를 유지
    private void saveShipment(int orderId, String company, String tracking, boolean allowCorrection) {
        // 취소 예약과 발송이 동시에 진행되지 않도록 같은 결제 행을 잠급니다.
        jdbc.queryForList("SELECT payment_id FROM payment WHERE order_id = ? FOR UPDATE", Integer.class, orderId);
        int updated = jdbc.update("""
                UPDATE order_delivery d JOIN orders o ON o.order_id = d.order_id
                JOIN payment p ON p.order_id = o.order_id
                SET d.tracking_company = ?, d.tracking_number = ?, d.shipped_at = COALESCE(d.shipped_at, CURRENT_TIMESTAMP),
                    d.delivery_status = CASE WHEN d.delivery_status = 'PREPARING' THEN 'SHIPPING' ELSE d.delivery_status END
                WHERE d.order_id = ? AND ((d.delivery_status = 'PREPARING' AND o.order_status = 'ORDERED')
                    OR (? AND d.delivery_status IN ('SHIPPING','DELIVERED') AND o.order_status IN ('ORDERED','CONFIRMED')))
                  AND p.payment_status = 'PAID'
                  AND NOT EXISTS (SELECT 1 FROM refund r WHERE r.payment_id = p.payment_id AND r.status = 'REQUESTED')
                """, company.strip(), tracking.strip(), orderId, allowCorrection);
        if (updated == 0) throw new InvalidStateException("현재 주문 상태에서는 송장을 저장할 수 없습니다.");
    }
}
