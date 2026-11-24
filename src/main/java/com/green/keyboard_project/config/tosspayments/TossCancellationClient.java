package com.green.keyboard_project.config.tosspayments;

import com.green.keyboard_project.dto.payment.OrderCancellationRequest.RefundAccount;
import com.green.keyboard_project.service.payment.TossCancellationGateway;
import com.green.keyboard_project.service.payment.TossCancellationGateway.Failure.Kind;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;

/** 검증된 테스트 시크릿을 사용하는 취소 전용 클라이언트입니다. */
public final class TossCancellationClient implements TossCancellationGateway {
    private static final String BASE_URL = "https://api.tosspayments.com";
    private final RestClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    public TossCancellationClient(TossPaymentsTestCredentials credentials) {
        this(credentials, transport());
    }

    TossCancellationClient(TossPaymentsTestCredentials credentials, RestClient.Builder builder) {
        http = builder.baseUrl(BASE_URL)
                .defaultHeaders(headers -> headers.setBasicAuth(credentials.secretKey(), "", StandardCharsets.UTF_8))
                .build();
    }

    private static RestClient.Builder transport() {
        // 인증 헤더를 다른 호스트에 전달하지 않도록 주소를 고정하고 리다이렉트를 끕니다.
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(65));
        return RestClient.builder().requestFactory(factory);
    }

    @Override
    public Snapshot lookup(String paymentKey) {
        return exchange(() -> http.get().uri("/v1/payments/{paymentKey}", paymentKey)
                .retrieve().toEntity(String.class));
    }

    @Override
    public Snapshot cancel(String paymentKey, String reason, Integer partialAmount,
                           RefundAccount account, String idempotencyKey) {
        var body = new LinkedHashMap<String, Object>();
        body.put("cancelReason", reason);
        // 전액 모드에서는 cancelAmount를 생략하고, 입금된 가상계좌에만 계좌정보를 전달합니다.
        if (partialAmount != null) body.put("cancelAmount", partialAmount);
        if (account != null) body.put("refundReceiveAccount", account);
        return exchange(() -> http.post().uri("/v1/payments/{paymentKey}/cancel", paymentKey)
                .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", idempotencyKey)
                .body(body).retrieve().toEntity(String.class));
    }

    private Snapshot exchange(Supplier<ResponseEntity<String>> request) {
        try {
            var response = request.get();
            if (response.getStatusCode().value() != 200) throw new Failure(Kind.INVALID_RESPONSE);
            return parse(json.readTree(response.getBody()));
        } catch (RestClientResponseException error) {
            throw new Failure(classify(error.getStatusCode().value(), safeCode(error)));
        } catch (ResourceAccessException error) {
            throw new Failure(isTimeout(error) ? Kind.TIMEOUT : Kind.NETWORK);
        } catch (Failure failure) {
            throw failure;
        } catch (RuntimeException error) {
            // 응답 원문이나 계좌번호가 담길 수 있는 원본 예외는 전달하지 않습니다.
            throw new Failure(Kind.INVALID_RESPONSE);
        }
    }

    // 문서에 정의된 코드만 분류합니다. 이미 취소됐거나 원인이 모호하면 재조회 전에 결과를 확정하지 않습니다.
    private static Kind classify(int status, String code) {
        if (status == 408 || status == 504) return Kind.TIMEOUT;
        if (status >= 500) return Kind.HTTP_5XX;
        if (status == 409 || status == 429) return Kind.IN_PROGRESS;
        if (status == 401) return Kind.AUTHENTICATION;
        if (status == 404) return Kind.PAYMENT_NOT_FOUND;
        return switch (code) {
            case "UNAUTHORIZED_KEY", "INVALID_API_KEY", "INCORRECT_BASIC_AUTH_FORMAT" -> Kind.AUTHENTICATION;
            case "FORBIDDEN_REQUEST" -> Kind.ACCESS_DENIED;
            case "NOT_FOUND_PAYMENT", "INVALID_PAYMENT_KEY" -> Kind.PAYMENT_NOT_FOUND;
            case "NOT_CANCELABLE_PAYMENT", "NOT_CANCELABLE_PAYMENT_FOR_DORMANT_USER" -> Kind.PAYMENT_STATE;
            case "EXCEED_MAX_REFUND_DUE" -> Kind.DEADLINE;
            case "NOT_ALLOWED_PARTIAL_REFUND", "NOT_PARTIAL_CANCELABLE_PAYMENT",
                    "NOT_ALLOWED_PARTIAL_REFUND_WAITING_DEPOSIT" -> Kind.PARTIAL_UNSUPPORTED;
            case "INVALID_REFUND_ACCOUNT_INFO", "INVALID_REFUND_ACCOUNT_NUMBER", "INVALID_BANK",
                    "FORBIDDEN_BANK_REFUND_REQUEST", "FAILED_CLOSED_ACCOUNT_REFUND" -> Kind.ACCOUNT_INVALID;
            case "NOT_CANCELABLE_AMOUNT", "EXCEED_CANCEL_AMOUNT_DISCOUNT_AMOUNT", "REFUND_REJECTED" -> Kind.REJECTED;
            case "ALREADY_CANCELED_PAYMENT", "ALREADY_REFUND_PAYMENT", "FORBIDDEN_CONSECUTIVE_REQUEST" -> Kind.IN_PROGRESS;
            default -> Kind.HTTP_4XX;
        };
    }

    private static boolean isTimeout(Throwable error) {
        for (var cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) return true;
        }
        return false;
    }

    private String safeCode(RestClientResponseException error) {
        try {
            return json.readTree(error.getResponseBodyAsString()).path("code").asText("");
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static Snapshot parse(JsonNode body) {
        if (body == null || !body.isObject()) throw new Failure(Kind.INVALID_RESPONSE);
        var history = new ArrayList<Cancellation>();
        JsonNode cancels = body.path("cancels");
        if (!cancels.isNull() && !cancels.isMissingNode()) {
            if (!cancels.isArray()) throw new Failure(Kind.INVALID_RESPONSE);
            for (JsonNode item : cancels) {
                history.add(new Cancellation(text(item, "transactionKey"), text(item, "cancelReason"),
                        amount(item, "cancelAmount"), OffsetDateTime.parse(text(item, "canceledAt")),
                        text(item, "cancelStatus")));
            }
        }
        return new Snapshot(text(body, "paymentKey"), text(body, "orderId"), text(body, "currency"),
                text(body, "status"), amount(body, "totalAmount"), amount(body, "balanceAmount"),
                body.path("isPartialCancelable").isBoolean() && body.path("isPartialCancelable").booleanValue(),
                "가상계좌".equals(text(body, "method")), List.copyOf(history));
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isTextual() || value.asText().isBlank()) throw new Failure(Kind.INVALID_RESPONSE);
        return value.asText();
    }

    private static int amount(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw new Failure(Kind.INVALID_RESPONSE);
        }
        return value.intValue();
    }
}
