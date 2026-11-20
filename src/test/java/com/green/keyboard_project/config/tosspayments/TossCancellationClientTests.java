package com.green.keyboard_project.config.tosspayments;

import com.green.keyboard_project.dto.payment.OrderCancellationRequest.RefundAccount;
import com.green.keyboard_project.service.payment.TossCancellationGateway.Failure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class TossCancellationClientTests {
    private static final String BASE = "https://api.tosspayments.com/v1/payments/mock-key";
    private static final String PAYMENT = """
            {"paymentKey":"mock-key","orderId":"ORD-TEST","currency":"KRW","status":"CANCELED",
             "method":"카드","totalAmount":100,"balanceAmount":0,"isPartialCancelable":true,"cancels":[
               {"transactionKey":"mock-transaction","cancelReason":"mock reason","cancelAmount":100,
                "canceledAt":"2026-01-01T00:00:00+09:00","cancelStatus":"DONE"}]}
            """;
    private MockRestServiceServer mockServer;
    private TossCancellationClient client;

    @BeforeEach
    void setUp() {
        // HTTP 요청을 테스트 내부에서 가로채므로 실제 Toss 네트워크에 연결하지 않습니다.
        var builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new TossCancellationClient(TossPaymentsTestCredentials.fromSecretKey("test_gsk_mock_only"), builder);
    }

    @Test
    void fullCancellationOmitsAmountAndReadsTransaction() {
        mockServer.expect(requestTo(BASE + "/cancel")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "mock-operation"))
                .andExpect(request -> {
                    assertThat(request.getHeaders().getFirst("Authorization")).startsWith("Basic ");
                    var body = JsonMapper.builder().build().readTree(((MockClientHttpRequest) request).getBodyAsString());
                    assertThat(body.path("cancelReason").asText()).isEqualTo("mock reason");
                    assertThat(body.has("cancelAmount")).isFalse();
                    assertThat(body.has("refundReceiveAccount")).isFalse();
                })
                .andRespond(withSuccess(PAYMENT, MediaType.APPLICATION_JSON));
        var snapshot = client.cancel("mock-key", "mock reason", null, null, "mock-operation");
        assertThat(snapshot.cancellations()).singleElement().satisfies(item -> {
            assertThat(item.transactionKey()).isEqualTo("mock-transaction");
            assertThat(item.amount()).isEqualTo(100);
        });
        mockServer.verify();
    }

    @Test
    void partialTransportIncludesAmountAndOnlyUsesSyntheticAccount() {
        var account = new RefundAccount("04", "000", "모의 계좌");
        mockServer.expect(requestTo(BASE + "/cancel")).andExpect(request -> {
            var body = JsonMapper.builder().build().readTree(((MockClientHttpRequest) request).getBodyAsString());
            assertThat(body.path("cancelAmount").intValue()).isEqualTo(20);
            assertThat(body.path("refundReceiveAccount").path("accountNumber").asText()).isEqualTo("000");
        }).andRespond(withSuccess(PAYMENT, MediaType.APPLICATION_JSON));
        client.cancel("mock-key", "mock reason", 20, account, "mock-operation");
        mockServer.verify();
    }

    @Test
    void alreadyCanceledIsUncertainAndProviderBodyIsNotExposed() {
        mockServer.expect(requestTo(BASE + "/cancel"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("""
                              {"code":"ALREADY_CANCELED_PAYMENT","message":"provider raw message"}
                              """));
        mockServer.expect(requestTo(BASE)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(PAYMENT, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.cancel("mock-key", "mock reason", null, null, "mock-operation"))
                .isInstanceOfSatisfying(Failure.class, error -> {
                    assertThat(error.uncertain()).isTrue();
                    assertThat(error.getMessage()).doesNotContain("provider raw message");
                });
        assertThat(client.lookup("mock-key").status()).isEqualTo("CANCELED");
        mockServer.verify();
    }
}
