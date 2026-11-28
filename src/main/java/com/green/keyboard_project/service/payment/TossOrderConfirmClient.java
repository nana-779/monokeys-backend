package com.green.keyboard_project.service.payment;

import com.green.keyboard_project.entity.Payment;
import com.green.keyboard_project.exceptions.InvalidStateException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * 실제 주문(ORD-...) 전용 토스페이먼츠 서버 최종 승인 클라이언트
 */
@Slf4j
@Service
public class TossOrderConfirmClient {

    private static final String CONFIRM_URL = "https://api.tosspayments.com/v1/payments/confirm";

    private final String secretKey;
    private final RestClient restClient;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public TossOrderConfirmClient(
            @Value("${TOSS_PAYMENTS_SECRET_KEY:${toss.payments.secret-key:}}") String secretKey
    ) {
        this.secretKey = secretKey != null ? secretKey.trim() : "";

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(60));

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * 토스페이먼츠 서버에 결제 최종 승인 요청을 전송하고 결제 수단 타입을 반환합니다.
     */
    public Payment.PaymentMethodType confirmPayment(String paymentKey, String orderNumber, int amount) {
        if (secretKey.isBlank()) {
            throw new InvalidStateException("토스페이먼츠 시크릿 키(TOSS_PAYMENTS_SECRET_KEY)가 설정되지 않았습니다.");
        }

        String encodedAuth = Base64.getEncoder()
                .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));

        try {
            String responseBody = restClient.post()
                    .uri(CONFIRM_URL)
                    .header("Authorization", "Basic " + encodedAuth)
                    .header("Idempotency-Key", orderNumber)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "paymentKey", paymentKey,
                            "orderId", orderNumber,
                            "amount", amount
                    ))
                    .retrieve()
                    .body(String.class);

            if (responseBody == null || responseBody.isBlank()) {
                throw new InvalidStateException("토스페이먼츠 승인 응답이 비어 있습니다.");
            }

            JsonNode root = jsonMapper.readTree(responseBody);
            String status = root.path("status").asText("");
            int totalAmount = root.path("totalAmount").asInt(-1);

            if (!"DONE".equals(status) && !"WAITING_FOR_DEPOSIT".equals(status)) {
                throw new InvalidStateException("결제 승인 상태가 올바르지 않습니다. (상태: " + status + ")");
            }

            if (totalAmount != amount) {
                throw new InvalidStateException("토스 결제 승인 금액이 주문 금액과 일치하지 않습니다.");
            }

            String method = root.path("method").asText("");
            return mapPaymentMethod(method);
        } catch (RestClientResponseException e) {
            String errorMessage = extractErrorMessage(e.getResponseBodyAsString());
            log.warn("토스페이먼츠 결제 승인 실패: status={}, orderNumber={}, message={}",
                    e.getStatusCode().value(), orderNumber, errorMessage);
            throw new InvalidStateException("토스 결제 승인 실패: " + errorMessage);
        } catch (RestClientException e) {
            log.error("토스페이먼츠 서버 통신 오류: orderNumber={}", orderNumber, e);
            throw new InvalidStateException("토스페이먼츠 서버와 통신 중 오류가 발생했습니다.");
        }
    }

    private Payment.PaymentMethodType mapPaymentMethod(String method) {
        if (method == null) {
            return Payment.PaymentMethodType.CARD;
        }
        return switch (method) {
            case "계좌이체" -> Payment.PaymentMethodType.TRANSFER;
            case "간편결제" -> Payment.PaymentMethodType.EASY_PAY;
            case "가상계좌" -> Payment.PaymentMethodType.VIRTUAL_ACCOUNT;
            default -> Payment.PaymentMethodType.CARD;
        };
    }

    private String extractErrorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "결제 승인이 거절되었습니다.";
        }
        try {
            JsonNode node = jsonMapper.readTree(body);
            String msg = node.path("message").asText("");
            if (!msg.isBlank()) {
                return msg;
            }
        } catch (Exception ignored) {
        }
        return "결제 승인이 거절되었습니다.";
    }

    /**
     * 결제 승인 후 재고 부족 등 서버 후속 처리 실패 시 즉시 자동 결제 취소(망취소)
     */
    public void cancelPayment(String paymentKey, String cancelReason) {
        String idempotencyKey = UUID.randomUUID().toString();
        try {
            String basicAuth = Base64.getEncoder().encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
            restClient.post()
                    .uri("https://api.tosspayments.com/v1/payments/{paymentKey}/cancel", paymentKey)
                    .header("Authorization", "Basic " + basicAuth)
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("cancelReason", cancelReason))
                    .retrieve()
                    .toBodilessEntity();
            log.info("토스페이먼츠 긴급 취소 완료: paymentKey={}, reason={}", paymentKey, cancelReason);
        } catch (Exception e) {
            log.error("토스페이먼츠 긴급 취소 실패! 관리자 확인 필요 (paymentKey={}, idempotencyKey={})", paymentKey, idempotencyKey, e);

            // 취소 실패 사실을 상위로 알려 모니터링/대사 대상이 되도록 처리
            throw new IllegalStateException("결제 취소 처리 중 오류가 발생했습니다.", e);
        }
    }
}
