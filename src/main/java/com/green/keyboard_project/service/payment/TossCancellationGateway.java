package com.green.keyboard_project.service.payment;

import com.green.keyboard_project.dto.payment.OrderCancellationRequest.RefundAccount;

import java.time.OffsetDateTime;
import java.util.List;

/** 결제사 취소 계약을 분리해 실제 통신 없이 취소 흐름을 검증할 수 있게 합니다. */
public interface TossCancellationGateway {
    Snapshot lookup(String paymentKey);

    Snapshot cancel(String paymentKey, String markedReason, Integer partialAmount,
                    RefundAccount account, String idempotencyKey);

    record Snapshot(String paymentKey, String orderNumber, String currency, String status,
                    int total, int balance, boolean partialAllowed, boolean virtualAccount,
                    List<Cancellation> cancellations) {
        @Override public String toString() { return "CancellationSnapshot[redacted]"; }
    }

    record Cancellation(String transactionKey, String reason, int amount, OffsetDateTime canceledAt, String status) {
        @Override public String toString() { return "Cancellation[redacted]"; }
    }

    /** 결과 미확정은 DB 요청 상태를 보존해 중복 취소를 막습니다. */
    final class Failure extends RuntimeException {
        private final boolean uncertain;

        public Failure(boolean uncertain) {
            super(uncertain ? "취소 결과를 확인할 수 없습니다." : "결제사가 취소 요청을 거절했습니다.");
            this.uncertain = uncertain;
        }

        public boolean uncertain() { return uncertain; }
    }
}
