package com.green.keyboard_project.config.tosspayments;

import com.green.keyboard_project.dto.payment.OrderCancellationRequest.RefundAccount;
import com.green.keyboard_project.service.payment.TossCancellationGateway;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** 기존 테스트 인증 정보를 사용하는 취소 전용 클라이언트입니다. 현재 Spring 빈으로 연결하지 않습니다. */
public final class TossCancellationClient implements TossCancellationGateway {
    private static final String BASE_URL = "https://api.tosspayments.com";
    private static final Set<String> CONFIRMED_REJECTIONS = Set.of(
            "UNAUTHORIZED_KEY", "INCORRECT_BASIC_AUTH_FORMAT", "INVALID_REFUND_ACCOUNT_INFO",
            "INVALID_REFUND_ACCOUNT_NUMBER", "INVALID_BANK", "NOT_CANCELABLE_AMOUNT", "REFUND_REJECTED"
    );

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
                .retrieve().toEntity(String.class), false);
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
                .body(body).retrieve().toEntity(String.class), true);
    }

    private Snapshot exchange(Supplier<ResponseEntity<String>> request, boolean cancellation) {
        try {
            var response = request.get();
            if (response.getStatusCode().value() != 200) throw new Failure(true);
            return parse(json.readTree(response.getBody()));
        } catch (RestClientResponseException error) {
            // 이미 취소됨·서버 오류·타임아웃은 원인이 확정되지 않으므로 재조회 대상으로 남깁니다.
            int status = error.getStatusCode().value();
            boolean rejected = cancellation && Set.of(400, 401, 403).contains(status)
                    && CONFIRMED_REJECTIONS.contains(safeCode(error));
            throw new Failure(!rejected);
        } catch (Failure failure) {
            throw failure;
        } catch (RuntimeException error) {
            // 응답 원문이나 계좌번호가 담길 수 있는 원본 예외는 전달하지 않습니다.
            throw new Failure(true);
        }
    }

    private String safeCode(RestClientResponseException error) {
        try {
            return json.readTree(error.getResponseBodyAsString()).path("code").asText("");
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static Snapshot parse(JsonNode body) {
        if (body == null || !body.isObject()) throw new Failure(true);
        var history = new ArrayList<Cancellation>();
        JsonNode cancels = body.path("cancels");
        if (!cancels.isNull() && !cancels.isMissingNode()) {
            if (!cancels.isArray()) throw new Failure(true);
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
        if (!value.isTextual() || value.asText().isBlank()) throw new Failure(true);
        return value.asText();
    }

    private static int amount(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) throw new Failure(true);
        return value.intValue();
    }
}
