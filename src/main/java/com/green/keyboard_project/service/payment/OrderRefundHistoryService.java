package com.green.keyboard_project.service.payment;

import com.green.keyboard_project.dto.payment.OrderRefundHistory;
import com.green.keyboard_project.dto.payment.OrderRefundHistory.Effect;
import com.green.keyboard_project.dto.payment.OrderRefundHistory.Entry;
import com.green.keyboard_project.dto.payment.OrderRefundHistory.Summary;
import com.green.keyboard_project.entity.Payment.PaymentStatusType;
import com.green.keyboard_project.entity.Refund;
import com.green.keyboard_project.entity.Refund.RefundStatusType;
import com.green.keyboard_project.exceptions.ResourceNotFoundException;
import com.green.keyboard_project.exceptions.UnauthenticatedException;
import com.green.keyboard_project.repository.RefundRepository;
import com.green.keyboard_project.repository.orders.OrderRepository;
import com.green.keyboard_project.repository.payment.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 본인 주문의 로컬 환불 원장을 읽어 누적 완료 금액과 거래별 결과를 계산합니다. */
@Service
@RequiredArgsConstructor
public class OrderRefundHistoryService {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final RefundRepository refunds;

    @Transactional(readOnly = true)
    public OrderRefundHistory history(String orderNumber, Integer memberId) {
        if (memberId == null) throw new UnauthenticatedException("로그인이 필요합니다.");
        // 주문번호만으로 조회하면 타인의 결제 존재 여부를 알려줄 수 있어 회원 조건을 함께 적용합니다.
        var order = orders.findByOrderNumberAndUser_UserIdAndUser_IsDeletedFalse(orderNumber, memberId)
                .orElseThrow(() -> new ResourceNotFoundException("주문을 찾을 수 없습니다."));
        var payment = payments.findByOrder_OrderId(order.getOrderId()).orElse(null);
        if (payment == null) {
            return new OrderRefundHistory(order.getOrderNumber(), null, null, null, Summary.NO_PAYMENT, List.of());
        }

        int total = payment.getPaidAmount();
        var sorted = refunds.findByPayment_PaymentIdOrderByRequestedAtDesc(payment.getPaymentId()).stream()
                .sorted(Comparator.comparing(OrderRefundHistoryService::eventTime).thenComparingInt(Refund::getRefundId))
                .toList();
        long completed = sorted.stream().filter(row -> row.getStatus() == RefundStatusType.COMPLETED)
                .mapToLong(Refund::getAmount).sum();
        boolean validAmounts = total >= 0 && completed >= 0 && completed <= total
                && sorted.stream().allMatch(row -> row.getAmount() > 0)
                && (total == 0 || payment.getPaymentStatus() != PaymentStatusType.CANCELLED || completed == total);
        boolean completeTimes = sorted.stream().noneMatch(row ->
                row.getStatus() == RefundStatusType.COMPLETED && row.getRefundedAt() == null);

        long runningTotal = 0;
        var entries = new ArrayList<Entry>(sorted.size());
        for (var row : sorted) {
            Effect effect = null;
            if (row.getStatus() == RefundStatusType.COMPLETED) {
                runningTotal += row.getAmount();
                // 취소 모드 컬럼 없이 금액이 결제 잔액을 소진한 마지막 거래를 전액 취소로 표시합니다.
                if (validAmounts && completeTimes) effect = runningTotal == total ? Effect.FULL : Effect.PARTIAL;
            }
            entries.add(new Entry(row.getRefundId(), row.getAmount(), row.getReason(), inKorea(row.getRequestedAt()),
                    inKorea(row.getRefundedAt()), row.getStatus(), effect));
        }

        Summary status;
        if (!validAmounts || !completeTimes) status = Summary.REVIEW_REQUIRED;
        else if (completed == 0) status = Summary.NONE;
        else status = completed == total ? Summary.FULL : Summary.PARTIAL;
        return new OrderRefundHistory(order.getOrderNumber(), total, validAmounts ? (int) completed : null,
                validAmounts ? total - (int) completed : null, status, List.copyOf(entries.reversed()));
    }

    private static LocalDateTime eventTime(Refund row) {
        return row.getRefundedAt() != null ? row.getRefundedAt() : row.getRequestedAt();
    }

    private static OffsetDateTime inKorea(LocalDateTime value) {
        return value == null ? null : value.atZone(KOREA).toOffsetDateTime();
    }
}
