package com.green.keyboard_project.service.payment;

import com.green.keyboard_project.dto.payment.OrderCancellationRequest;
import com.green.keyboard_project.dto.payment.OrderCancellationRequest.Mode;
import com.green.keyboard_project.exceptions.PaymentConfirmationException;
import com.green.keyboard_project.service.payment.TossCancellationGateway.Snapshot;

/** 결제사의 확인된 상태와 잔액으로 전액·부분 취소 요청을 구분합니다. */
final class OrderCancellationPolicy {
    private OrderCancellationPolicy() {}

    static Integer providerAmount(OrderCancellationRequest request, Snapshot snapshot) {
        int amount = request.amountFor(snapshot.balance());
        boolean waiting = "WAITING_FOR_DEPOSIT".equals(snapshot.status());
        if (waiting) {
            // 미입금 가상계좌에는 돌려줄 돈이 없어 전체 취소만 가능하며 계좌정보를 받지 않습니다.
            if (!snapshot.virtualAccount() || request.mode() != Mode.FULL || request.refundReceiveAccount() != null) {
                throw invalid("미입금 가상계좌는 환불계좌 없이 전액 취소만 가능합니다.");
            }
            return null;
        }
        if (!"DONE".equals(snapshot.status()) && !"PARTIAL_CANCELED".equals(snapshot.status())) {
            throw new PaymentConfirmationException(409, "PROVIDER_PAYMENT_NOT_CANCELABLE",
                    "Toss 결제 상태에서 취소할 수 없습니다.");
        }
        if (snapshot.virtualAccount() != (request.refundReceiveAccount() != null)) {
            throw invalid(snapshot.virtualAccount() ? "입금된 가상계좌에는 환불계좌가 필요합니다."
                    : "가상계좌 결제에서만 환불계좌를 사용할 수 있습니다.");
        }
        if (request.mode() == Mode.FULL) return null;
        if (!snapshot.partialAllowed()) throw invalid("부분 취소를 지원하지 않는 결제입니다.");
        if (amount == snapshot.balance()) throw invalid("잔여 금액을 모두 취소하려면 전액 취소를 선택해 주세요.");
        return amount;
    }

    private static PaymentConfirmationException invalid(String message) {
        return new PaymentConfirmationException(400, "CANCEL_POLICY_REJECTED", message);
    }
}
