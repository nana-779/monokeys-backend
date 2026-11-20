package com.green.keyboard_project.dto.payment;

import com.green.keyboard_project.exceptions.PaymentConfirmationException;
import tools.jackson.databind.JsonNode;

/** 주문번호는 경로에서 받으며, 결제 키는 서버에 저장된 값만 사용합니다. */
public record OrderCancellationRequest(Mode mode, String cancelReason, Integer cancelAmount,
                                       int expectedRemainingAmount, RefundAccount refundReceiveAccount) {
    public enum Mode { FULL, PARTIAL }

    public OrderCancellationRequest {
        if (mode == null || !validText(cancelReason, 140) || expectedRemainingAmount <= 0
                || (mode == Mode.FULL && cancelAmount != null)
                || (mode == Mode.PARTIAL && (cancelAmount == null || cancelAmount <= 0))) {
            throw invalid();
        }
        cancelReason = cancelReason.strip();
    }

    // 정수가 아닌 JSON 값과 클라이언트에서 보낸 결제 키를 입력 단계에서 차단합니다.
    public static OrderCancellationRequest fromJson(JsonNode body) {
        if (body == null || !body.isObject() || body.has("paymentKey")) throw invalid();
        Mode mode = switch (body.path("mode").asText("")) {
            case "FULL" -> Mode.FULL;
            case "PARTIAL" -> Mode.PARTIAL;
            default -> throw invalid();
        };
        JsonNode amount = body.path("cancelAmount");
        JsonNode account = body.path("refundReceiveAccount");
        return new OrderCancellationRequest(mode, text(body, "cancelReason"),
                absent(amount) ? null : integer(amount), integer(body.path("expectedRemainingAmount")),
                absent(account) ? null : new RefundAccount(text(account, "bank"),
                        text(account, "accountNumber"), text(account, "holderName")));
    }

    // 이전 요청으로 잔액이 바뀌면 같은 요청이 다시 들어와도 취소하지 않습니다.
    public int amountFor(int remaining) {
        if (remaining <= 0 || remaining != expectedRemainingAmount) {
            throw new PaymentConfirmationException(409, "REFUND_BALANCE_CHANGED", "현재 취소 가능 잔액을 다시 확인해 주세요.");
        }
        if (mode == Mode.FULL) return remaining;
        if (cancelAmount > remaining) {
            throw new PaymentConfirmationException(400, "INVALID_CANCEL_AMOUNT", "취소 금액이 남은 결제 금액을 초과했습니다.");
        }
        return cancelAmount;
    }

    /** 계좌정보는 PG 요청에만 전달하며 로그나 DB 기록에 남기지 않습니다. */
    public record RefundAccount(String bank, String accountNumber, String holderName) {
        public RefundAccount {
            if (bank == null || !bank.matches("[A-Z0-9]{2,12}") || accountNumber == null
                    || !accountNumber.matches("[0-9]{1,20}") || !validText(holderName, 60)) {
                throw invalid();
            }
            holderName = holderName.strip();
        }

        @Override public String toString() { return "RefundAccount[redacted]"; }
    }

    private static boolean validText(String value, int maxLength) {
        return value != null && !value.isBlank() && value.length() <= maxLength
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean absent(JsonNode node) {
        return node.isMissingNode() || node.isNull();
    }

    private static int integer(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToInt()) throw invalid();
        return node.intValue();
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).isTextual() ? node.path(field).asText() : null;
    }

    private static PaymentConfirmationException invalid() {
        return new PaymentConfirmationException(400, "INVALID_CANCEL_REQUEST", "취소 요청의 사유, 금액 또는 계좌 형식을 확인해 주세요.");
    }

    @Override public String toString() { return "OrderCancellationRequest[redacted]"; }
}
