package com.green.keyboard_project.service.payment;

import com.green.keyboard_project.dto.payment.OrderCancellationRequest;
import com.green.keyboard_project.dto.payment.OrderCancellationRequest.Mode;
import com.green.keyboard_project.dto.payment.OrderCancellationRequest.RefundAccount;
import com.green.keyboard_project.exceptions.PaymentConfirmationException;
import com.green.keyboard_project.service.payment.OrderCancellationStore.Pending;
import com.green.keyboard_project.service.payment.OrderCancellationStore.Target;
import com.green.keyboard_project.service.payment.TossCancellationGateway.Cancellation;
import com.green.keyboard_project.service.payment.TossCancellationGateway.Failure;
import com.green.keyboard_project.service.payment.TossCancellationGateway.Snapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 실제 DB 및 결제사 없이 취소·재조회 흐름을 검증합니다. */
class OrderCancellationServiceTests {
    private static final Target TARGET = new Target(21, "ORD-TEST", "synthetic-key", 10_000, 10_000);
    private static final OffsetDateTime CANCELLED_AT = OffsetDateTime.parse("2026-01-01T00:00:00+09:00");
    private static final String REASON = "주문 취소 요청";
    private static final String MARKED_REASON = REASON + " [MK-R31]";
    private static final String IDEMPOTENCY = "monokeys-refund-"
            + UUID.nameUUIDFromBytes("synthetic-key:31".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final OrderCancellationRequest FULL = new OrderCancellationRequest(Mode.FULL, REASON, null, 10_000, null);

    private final OrderCancellationStore store = mock(OrderCancellationStore.class);
    private final TossCancellationGateway gateway = mock(TossCancellationGateway.class);
    private final OrderCancellationService service = new OrderCancellationService(store,
            new StaticListableBeanFactory(Map.of("testGateway", gateway)).getBeanProvider(TossCancellationGateway.class));

    @Test
    void defaultApplicationGatewayIsDisabledBeforeReservation() {
        when(store.inspect("ORD-TEST", 7)).thenReturn(TARGET);
        var disabled = new OrderCancellationService(store,
                new StaticListableBeanFactory().getBeanProvider(TossCancellationGateway.class));
        assertThatThrownBy(() -> disabled.cancel("ORD-TEST", 7, FULL))
                .isInstanceOfSatisfying(PaymentConfirmationException.class, e -> assertThat(e.status()).isEqualTo(503));
        verify(store, never()).reserve(any(), any(), any());
        verifyNoInteractions(gateway);
    }

    @Test
    void entireCancellationUsesStoredKeyAndVerifiesTransaction() {
        reserve(FULL);
        Cancellation transaction = cancellation(MARKED_REASON, 10_000);
        when(gateway.cancel("synthetic-key", MARKED_REASON, null, null, IDEMPOTENCY))
                .thenReturn(snapshot("CANCELED", 0, false, transaction));

        var result = service.cancel("ORD-TEST", 7, FULL);

        assertThat(result.status()).isEqualTo("CANCELLATION_CONFIRMED");
        var order = inOrder(store, gateway);
        order.verify(store).inspect("ORD-TEST", 7);
        order.verify(gateway).lookup("synthetic-key");
        order.verify(store).reserve(TARGET, 7, FULL);
        order.verify(gateway).cancel("synthetic-key", MARKED_REASON, null, null, IDEMPOTENCY);
        order.verify(store).complete(21, 31, transaction);
    }

    @Test
    void partialRequestUsesAmountAndReplayWithOlderBalanceIsBlocked() {
        var partial = new OrderCancellationRequest(Mode.PARTIAL, REASON, 2_000, 10_000, null);
        reserve(partial);
        when(gateway.cancel("synthetic-key", MARKED_REASON, 2_000, null, IDEMPOTENCY))
                .thenReturn(snapshot("PARTIAL_CANCELED", 8_000, false, cancellation(MARKED_REASON, 2_000)));
        assertThat(service.cancel("ORD-TEST", 7, partial).amount()).isEqualTo(2_000);

        when(store.inspect("ORD-TEST", 7)).thenReturn(new Target(21, "ORD-TEST", "synthetic-key", 10_000, 8_000));
        assertThatThrownBy(() -> service.cancel("ORD-TEST", 7, partial))
                .isInstanceOfSatisfying(PaymentConfirmationException.class,
                        e -> assertThat(e.code()).isEqualTo("REFUND_BALANCE_CHANGED"));
        verify(gateway, times(1)).cancel(any(), any(), any(), any(), any());
    }

    @Test
    void changedBalanceOrConcurrentReservationPreventsCancellationPost() {
        reserve(FULL);
        when(store.reserve(TARGET, 7, FULL)).thenThrow(
                new PaymentConfirmationException(409, "REFUND_IN_PROGRESS", "처리 중입니다."));
        assertThatThrownBy(() -> service.cancel("ORD-TEST", 7, FULL)).isInstanceOf(PaymentConfirmationException.class);
        verify(gateway, never()).cancel(any(), any(), any(), any(), any());
    }

    @Test
    void timeoutTriggersLookupOnlyAndDoesNotReportSuccessWithoutExactMatch() {
        reserve(FULL);
        when(gateway.lookup("synthetic-key")).thenReturn(snapshot("DONE", 10_000, false))
                .thenReturn(snapshot("CANCELED", 0, false, cancellation("another cancellation", 10_000)));
        when(gateway.cancel("synthetic-key", MARKED_REASON, null, null, IDEMPOTENCY)).thenThrow(new Failure(true));
        assertThat(service.cancel("ORD-TEST", 7, FULL).status()).isEqualTo("PENDING_CONFIRMATION");
        verify(gateway, times(2)).lookup("synthetic-key");
        verify(gateway, times(1)).cancel(any(), any(), any(), any(), any());
        verify(store, never()).complete(anyInt(), anyInt(), any());
        verify(store, never()).reject(anyInt(), anyInt());
    }

    @Test
    void recoveryUsesStoredReservationAndDoesNotPostCancellation() {
        Cancellation transaction = cancellation(MARKED_REASON, 10_000);
        when(store.pendingForRecovery("ORD-TEST", 7)).thenReturn(new Pending(TARGET, 31, 10_000, REASON));
        when(gateway.lookup("synthetic-key")).thenReturn(snapshot("CANCELED", 0, false, transaction));
        assertThat(service.reconcile("ORD-TEST", 7).status()).isEqualTo("CANCELLATION_CONFIRMED");
        verify(gateway, never()).cancel(any(), any(), any(), any(), any());
        verify(store).complete(21, 31, transaction);
    }

    @Test
    void committedProviderSuccessWithLocalWriteFailureReturnsPending() {
        reserve(FULL);
        var transaction = cancellation(MARKED_REASON, 10_000);
        when(gateway.cancel("synthetic-key", MARKED_REASON, null, null, IDEMPOTENCY))
                .thenReturn(snapshot("CANCELED", 0, false, transaction));
        doThrow(new IllegalStateException("mock store failure")).when(store).complete(21, 31, transaction);
        assertThat(service.cancel("ORD-TEST", 7, FULL).status()).isEqualTo("PENDING_CONFIRMATION");
        verify(store, never()).reject(anyInt(), anyInt());
    }

    @Test
    void definitiveRejectionMarksOnlyTheReservedRow() {
        reserve(FULL);
        when(gateway.cancel("synthetic-key", MARKED_REASON, null, null, IDEMPOTENCY)).thenThrow(new Failure(false));
        assertThatThrownBy(() -> service.cancel("ORD-TEST", 7, FULL))
                .isInstanceOfSatisfying(PaymentConfirmationException.class, e -> assertThat(e.status()).isEqualTo(422));
        verify(store).reject(21, 31);
    }

    @Test
    void amountAndVirtualAccountRulesRejectUnsafeInputs() {
        var account = new RefundAccount("04", "000000", "모의 예금주");
        var paidVirtual = snapshot("DONE", 10_000, true);
        var unpaidVirtual = snapshot("WAITING_FOR_DEPOSIT", 10_000, true);
        var fullWithAccount = new OrderCancellationRequest(Mode.FULL, REASON, null, 10_000, account);
        var partial = new OrderCancellationRequest(Mode.PARTIAL, REASON, 100, 10_000, null);
        assertThat(OrderCancellationPolicy.providerAmount(FULL, unpaidVirtual)).isNull();
        assertThat(OrderCancellationPolicy.providerAmount(fullWithAccount, paidVirtual)).isNull();
        assertThatThrownBy(() -> OrderCancellationPolicy.providerAmount(FULL, paidVirtual))
                .isInstanceOf(PaymentConfirmationException.class);
        assertThatThrownBy(() -> OrderCancellationPolicy.providerAmount(fullWithAccount, unpaidVirtual))
                .isInstanceOf(PaymentConfirmationException.class);
        assertThatThrownBy(() -> OrderCancellationPolicy.providerAmount(partial, unpaidVirtual))
                .isInstanceOf(PaymentConfirmationException.class);
        assertThatThrownBy(() -> partial.amountFor(99)).isInstanceOf(PaymentConfirmationException.class);
        assertThatThrownBy(() -> new OrderCancellationRequest(Mode.PARTIAL, REASON, 10_001, 10_000, null)
                .amountFor(10_000)).isInstanceOf(PaymentConfirmationException.class);
        assertThat(account.toString()).doesNotContain(account.accountNumber(), account.holderName());
    }

    @Test
    void jsonRejectsBrowserPaymentKeyAndNonIntegralOrBlankInput() {
        var json = JsonMapper.builder().build();
        for (String body : List.of(
                """
                {"mode":"FULL","cancelReason":"reason","expectedRemainingAmount":10,"paymentKey":"from-browser"}
                """,
                """
                {"mode":"PARTIAL","cancelReason":"reason","expectedRemainingAmount":10,"cancelAmount":1.5}
                """,
                """
                {"mode":"FULL","cancelReason":" ","expectedRemainingAmount":10}
                """)) {
            assertThatThrownBy(() -> OrderCancellationRequest.fromJson(json.readTree(body)))
                    .isInstanceOfSatisfying(PaymentConfirmationException.class, e -> assertThat(e.status()).isEqualTo(400));
        }
    }

    private void reserve(OrderCancellationRequest request) {
        when(store.inspect("ORD-TEST", 7)).thenReturn(TARGET);
        when(store.reserve(TARGET, 7, request)).thenReturn(31);
        when(gateway.lookup("synthetic-key")).thenReturn(snapshot("DONE", 10_000, false));
    }

    private static Snapshot snapshot(String state, int balance, boolean virtual, Cancellation... rows) {
        return new Snapshot("synthetic-key", "ORD-TEST", "KRW", state, 10_000, balance, true, virtual, List.of(rows));
    }

    private static Cancellation cancellation(String reason, int amount) {
        return new Cancellation("test-tx", reason, amount, CANCELLED_AT, "DONE");
    }
}
