package com.green.keyboard_project.service.payment;

import com.green.keyboard_project.dto.payment.OrderCancellationRequest;
import com.green.keyboard_project.exceptions.PaymentConfirmationException;
import com.green.keyboard_project.service.payment.OrderCancellationStore.Target;
import com.green.keyboard_project.service.payment.TossCancellationGateway.Cancellation;
import com.green.keyboard_project.service.payment.TossCancellationGateway.Failure;
import com.green.keyboard_project.service.payment.TossCancellationGateway.Snapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 본인 결제의 취소를 조율합니다. 결제사 연결이 준비되기 전에는 조회와 유효성 검사까지만 실행합니다. */
@Service
@RequiredArgsConstructor
public class OrderCancellationService {
    private final OrderCancellationStore store;
    private final ObjectProvider<TossCancellationGateway> gateways;

    public record Result(int refundId, String status, int amount) {}

    @Transactional(propagation = Propagation.NEVER)
    public Result cancel(String orderNumber, Integer memberId, OrderCancellationRequest request) {
        Target target = store.inspect(orderNumber, memberId);
        int amount = request.amountFor(target.remaining());
        TossCancellationGateway gateway = configuredGateway();

        Snapshot before;
        try {
            before = gateway.lookup(target.paymentKey());
        } catch (Failure error) {
            throw new PaymentConfirmationException(502, "PROVIDER_LOOKUP_FAILED", "Toss 결제 상태를 확인할 수 없습니다.");
        }
        if (!matchesPayment(target, before) || before.virtualAccount() || before.balance() != target.remaining()) {
            throw new PaymentConfirmationException(409, "PROVIDER_PAYMENT_CHANGED", "Toss 결제 잔액 또는 상태를 다시 확인해 주세요.");
        }
        Integer providerAmount = OrderCancellationPolicy.providerAmount(request, before);
        int refundId = store.reserve(target, memberId, request);
        // DB 요청 번호로 고정한 멱등키와 식별 문구를 사용해 재조회에서 같은 금액의 다른 취소와 구분합니다.
        String idempotencyKey = "monokeys-refund-" + UUID.nameUUIDFromBytes(
                (target.paymentKey() + ":" + refundId).getBytes(StandardCharsets.UTF_8));
        String markedReason = markedReason(request.cancelReason(), refundId);
        Set<String> previousKeys = before.cancellations().stream().map(Cancellation::transactionKey)
                .collect(Collectors.toSet());
        Cancellation confirmed;
        try {
            Snapshot result = gateway.cancel(target.paymentKey(), markedReason, providerAmount,
                    request.refundReceiveAccount(), idempotencyKey);
            confirmed = findConfirmed(target, amount, markedReason, previousKeys, result);
        } catch (Failure failure) {
            if (!failure.uncertain()) {
                try {
                    store.reject(target.paymentId(), refundId);
                } catch (RuntimeException storageFailure) {
                    return new Result(refundId, "PENDING_CONFIRMATION", amount);
                }
                throw new PaymentConfirmationException(422, "PROVIDER_CANCEL_REJECTED", failure.getMessage());
            }
            confirmed = null;
        }

        // 응답을 못 받았거나 거래를 식별할 수 없으면 조회만 한 번 수행합니다. 취소 POST는 반복하지 않습니다.
        if (confirmed == null) {
            try {
                confirmed = findConfirmed(target, amount, markedReason, previousKeys, gateway.lookup(target.paymentKey()));
            } catch (Failure ignored) {
                return new Result(refundId, "PENDING_CONFIRMATION", amount);
            }
        }
        return persistOutcome(target, refundId, amount, confirmed);
    }

    // 네트워크 장애나 서버 재시작 뒤에도 저장된 요청을 재조회하며 취소 POST를 다시 전송하지 않습니다.
    @Transactional(propagation = Propagation.NEVER)
    public Result reconcile(String orderNumber, Integer memberId) {
        var pending = store.pendingForRecovery(orderNumber, memberId);
        TossCancellationGateway gateway = configuredGateway();
        Cancellation confirmed;
        try {
            confirmed = findConfirmed(pending.target(), pending.amount(), markedReason(pending.reason(), pending.refundId()),
                    Set.of(), gateway.lookup(pending.target().paymentKey()));
        } catch (Failure ignored) {
            confirmed = null;
        }
        return persistOutcome(pending.target(), pending.refundId(), pending.amount(), confirmed);
    }

    private Result persistOutcome(Target target, int refundId, int amount, Cancellation confirmed) {
        if (confirmed == null) return new Result(refundId, "PENDING_CONFIRMATION", amount);
        try {
            store.complete(target.paymentId(), refundId, confirmed);
        } catch (RuntimeException storageFailure) {
            // PG가 취소됐어도 로컬 기록을 확정하지 못했다면 요청 행을 남겨 후속 대사를 기다립니다.
            return new Result(refundId, "PENDING_CONFIRMATION", amount);
        }
        return new Result(refundId, "CANCELLATION_CONFIRMED", amount);
    }

    private TossCancellationGateway configuredGateway() {
        // 결제·재고·배송 후속 정책이 확정될 때까지 실제 클라이언트를 컨테이너에 등록하지 않습니다.
        TossCancellationGateway gateway = gateways.getIfAvailable();
        if (gateway == null) {
            throw new PaymentConfirmationException(503, "CANCELLATION_NOT_ENABLED", "결제 취소 기능은 준비 중입니다.");
        }
        return gateway;
    }

    private static String markedReason(String reason, int refundId) {
        return reason + " [MK-R" + refundId + "]";
    }

    private static boolean matchesPayment(Target target, Snapshot payment) {
        return payment != null && Objects.equals(target.paymentKey(), payment.paymentKey())
                && Objects.equals(target.orderNumber(), payment.orderNumber())
                && "KRW".equals(payment.currency()) && target.total() == payment.total();
    }

    // 상태·잔액과 이번 요청의 유일한 취소 거래를 모두 검증한 경우만 완료할 수 있습니다.
    private static Cancellation findConfirmed(Target target, int amount, String markedReason,
                                              Set<String> previousKeys, Snapshot after) {
        int expectedBalance = target.remaining() - amount;
        String expectedStatus = expectedBalance == 0 ? "CANCELED" : "PARTIAL_CANCELED";
        if (!matchesPayment(target, after) || after.virtualAccount() || after.balance() != expectedBalance
                || !expectedStatus.equals(after.status())) return null;
        var matches = after.cancellations().stream()
                .filter(row -> markedReason.equals(row.reason()) && amount == row.amount()
                        && "DONE".equals(row.status()) && row.canceledAt() != null
                        && row.transactionKey() != null && row.transactionKey().matches("[!-~]{1,64}")
                        && !previousKeys.contains(row.transactionKey()))
                .toList();
        if (matches.size() != 1) return null;
        Cancellation match = matches.getFirst();
        long sameKeyCount = after.cancellations().stream()
                .filter(row -> match.transactionKey().equals(row.transactionKey())).count();
        return sameKeyCount == 1 ? match : null;
    }
}
