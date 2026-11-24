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
        public enum Kind {
            AUTHENTICATION(false, 502, "결제사 인증 설정을 확인해야 합니다."),
            ACCESS_DENIED(false, 502, "결제사에서 취소 API 접근을 허용하지 않았습니다."),
            PAYMENT_NOT_FOUND(true, 409, "결제사에서 결제를 찾을 수 없습니다."),
            PAYMENT_STATE(false, 409, "현재 결제 상태에서는 취소할 수 없습니다."),
            DEADLINE(false, 422, "결제수단의 취소 가능 기간이 지났습니다."),
            PARTIAL_UNSUPPORTED(false, 422, "이 거래는 부분 취소가 불가능합니다."),
            ACCOUNT_INVALID(false, 422, "환불계좌 정보를 확인해 주세요."),
            REJECTED(false, 422, "결제사가 취소 요청을 거절했습니다."),
            IN_PROGRESS(true, 502, "결제사의 기존 취소 결과를 확인해야 합니다."),
            HTTP_4XX(true, 502, "결제사 요청 처리 결과를 확인할 수 없습니다."),
            HTTP_5XX(true, 502, "결제사 서버 오류로 결과를 확인할 수 없습니다."),
            NETWORK(true, 502, "결제사 연결 오류로 결과를 확인할 수 없습니다."),
            TIMEOUT(true, 502, "결제사 응답이 지연되어 결과를 확인할 수 없습니다."),
            INVALID_RESPONSE(true, 502, "결제사 응답을 확인할 수 없습니다.");

            private final boolean uncertain;
            private final int status;
            private final String safeMessage;

            Kind(boolean uncertain, int status, String safeMessage) {
                this.uncertain = uncertain;
                this.status = status;
                this.safeMessage = safeMessage;
            }
        }

        private final Kind kind;

        public Failure(Kind kind) {
            super(kind.safeMessage);
            this.kind = kind;
        }

        public boolean uncertain() { return kind.uncertain; }
        public int status() { return kind.status; }
        public String code() { return "TOSS_" + kind.name(); }
    }
}
