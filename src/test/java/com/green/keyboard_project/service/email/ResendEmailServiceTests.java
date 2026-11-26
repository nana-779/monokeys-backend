package com.green.keyboard_project.service.email;

import com.resend.Resend;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.green.keyboard_project.service.email.ResendEmailService.Failure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * 실제 SDK를 사용해 발송 서비스의 요청과 응답 처리를 확인합니다.
 * 네트워크가 필요한 테스트는 가짜 키와 로컬 모의 서버만 사용합니다.
 */
class ResendEmailServiceTests {
    private static final String TEST_KEY = "re_mock_key";
    private static final String SENDER = "Monokeys <signup@example.com>";
    private static final String RECIPIENT = "customer@example.com";
    private static final String CODE = "012345";

    // 모의 서버가 받은 요청을 테스트 스레드에서 확인할 수 있도록 보관합니다.
    private final AtomicReference<Request> request = new AtomicReference<>();
    private HttpServer mockServer;

    // 테스트에서 열었던 모의 서버가 다음 테스트에 영향을 주지 않도록 닫습니다.
    @AfterEach
    void stopMockServer() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    // 키가 없을 때 서비스 빈은 만들어지고 실제 발송을 호출할 때만 설정 오류가 나는지 확인합니다.
    @Test
    void springCreatesServiceWithoutApiKeyAndSendingFailsSafely() {
        try (var context = new AnnotationConfigApplicationContext()) {
            // 테스트 컨텍스트에서만 시스템 설정을 제외해 실제 키가 있어도 누락 상황을 재현합니다.
            var sources = context.getEnvironment().getPropertySources();
            sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            sources.addFirst(new MapPropertySource("resend-test-settings", Map.of("EMAIL_FROM", SENDER)));
            context.register(ResendEmailService.class);
            context.refresh();

            var service = context.getBean(ResendEmailService.class);
            assertThatExceptionOfType(ResendEmailService.EmailDeliveryException.class)
                    .isThrownBy(() -> service.sendVerificationCode(RECIPIENT, CODE))
                    .satisfies(error -> assertThat(error.getFailure()).isEqualTo(Failure.NOT_CONFIGURED));
        }
    }

    // 키가 있어도 발신 주소가 비어 있으면 발송을 거절하는지 확인합니다.
    @Test
    void sendingWithoutSenderFailsSafely() {
        var service = new ResendEmailService(TEST_KEY, " ");

        assertThatExceptionOfType(ResendEmailService.EmailDeliveryException.class)
                .isThrownBy(() -> service.sendVerificationCode(RECIPIENT, CODE))
                .satisfies(error -> assertThat(error.getFailure()).isEqualTo(Failure.NOT_CONFIGURED));
    }

    // 접수 ID와 HTTP 요청을 확인합니다. 수신자는 한 명이고 본문의 인증번호는 앞자리 0을 유지해야 합니다.
    @Test
    void sendsOneEmailWithTheLeadingZeroAndReturnsTheProviderId() throws IOException {
        var service = mockService(200, "{\"id\":\"mock-delivery-id\"}");

        assertThat(service.sendVerificationCode(RECIPIENT, CODE)).isEqualTo("mock-delivery-id");
        Request sent = request.get();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.authorization()).isEqualTo("Bearer " + TEST_KEY);
        var body = new ObjectMapper().readTree(sent.body());
        assertThat(body.path("from").stringValue()).isEqualTo(SENDER);
        assertThat(body.path("to").size()).isEqualTo(1);
        assertThat(body.path("to").path(0).stringValue()).isEqualTo(RECIPIENT);
        assertThat(body.path("subject").stringValue()).contains("Monokeys");
        assertThat(body.path("text").stringValue()).contains(CODE);
    }

    // 수신자가 여러 명이거나 번호가 6자리가 아니면 모의 서버에도 요청이 도착하지 않아야 합니다.
    @Test
    void rejectsInvalidMessageBeforeContactingTheProvider() throws IOException {
        var service = mockService(200, "{\"id\":\"unexpected\"}");

        assertThatExceptionOfType(ResendEmailService.EmailDeliveryException.class)
                .isThrownBy(() -> service.sendVerificationCode(RECIPIENT + ",other@example.com", CODE))
                .satisfies(error -> assertThat(error.getFailure()).isEqualTo(Failure.INVALID_MESSAGE));
        assertThatExceptionOfType(ResendEmailService.EmailDeliveryException.class)
                .isThrownBy(() -> service.sendVerificationCode(RECIPIENT, "12345"))
                .satisfies(error -> assertThat(error.getFailure()).isEqualTo(Failure.INVALID_MESSAGE));
        assertThat(request.get()).isNull();
    }

    // 발송 거절 응답을 받아도 밖으로 전달되는 예외에 가짜 키·주소·번호·원문이 남지 않는지 확인합니다.
    @Test
    void providerRejectionDoesNotExposeRequestDetailsOrRawError() throws IOException {
        var service = mockService(403,
                "{\"name\":\"validation_error\",\"message\":\"re_mock_key customer@example.com 012345 provider-only\"}");

        assertThatExceptionOfType(ResendEmailService.EmailDeliveryException.class)
                .isThrownBy(() -> service.sendVerificationCode(RECIPIENT, CODE))
                .withNoCause()
                .withMessageNotContaining(TEST_KEY)
                .withMessageNotContaining(RECIPIENT)
                .withMessageNotContaining(CODE)
                .withMessageNotContaining("provider-only")
                .satisfies(error -> assertThat(error.getFailure()).isEqualTo(Failure.SEND_FAILED));
        assertThat(request.get()).isNotNull();
    }

    // HTTP 상태가 성공이어도 접수 ID가 빠져 있으면 발송 실패로 처리하는지 확인합니다.
    @Test
    void treatsMissingDeliveryIdAsFailure() throws IOException {
        var service = mockService(200, "{}");

        assertThatExceptionOfType(ResendEmailService.EmailDeliveryException.class)
                .isThrownBy(() -> service.sendVerificationCode(RECIPIENT, CODE))
                .satisfies(error -> assertThat(error.getFailure()).isEqualTo(Failure.SEND_FAILED));
    }

    private ResendEmailService mockService(int status, String response) throws IOException {
        // 로컬에서 비어 있는 포트를 골라 요청을 받고, 테스트가 지정한 상태 코드와 JSON을 돌려줍니다.
        mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockServer.createContext("/emails", exchange -> {
            try (exchange) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                request.set(new Request(exchange.getRequestMethod(),
                        exchange.getRequestHeaders().getFirst("Authorization"), body));
                byte[] payload = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, payload.length);
                exchange.getResponseBody().write(payload);
            }
        });
        mockServer.start();

        // SDK의 발송 URL을 모의 서버로 지정해 요청 생성과 응답 처리를 함께 확인합니다.
        var resend = Resend.builder()
                .apiKey(TEST_KEY)
                .baseUrl("http://127.0.0.1:" + mockServer.getAddress().getPort())
                .callTimeout(Duration.ofSeconds(3))
                .build();
        return new ResendEmailService(resend, SENDER);
    }

    private record Request(String method, String authorization, String body) {}
}
