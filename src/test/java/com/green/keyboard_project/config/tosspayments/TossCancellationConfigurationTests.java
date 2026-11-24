package com.green.keyboard_project.config.tosspayments;

import com.green.keyboard_project.service.payment.TossCancellationGateway;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TossCancellationConfigurationTests {
    @Test
    void gatewayRequiresBothOptInsAndLocalProfileWithoutProduction() {
        assertGateway(false, false, false, "local");
        assertGateway(false, false, true, "local");
        assertGateway(false, true, false, "local");
        assertGateway(false, true, true);
        assertGateway(false, true, true, "local", "prod");
        assertGateway(false, true, true, "local", "production");
        assertGateway(true, true, true, "local");
    }

    @Test
    void configurationRejectsProductionSecret() {
        try (var context = context(true, true, "live_gsk_mock_only", "local")) {
            assertThatThrownBy(context::refresh)
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("운영 키는 결제 테스트 설정에서 사용할 수 없습니다.");
        }
    }

    private static void assertGateway(boolean expected, boolean testEnabled, boolean cancelEnabled, String... profiles) {
        // 이 테스트는 설정 클래스의 빈 등록만 확인하며 DB나 HTTP 요청을 사용하지 않습니다.
        try (var context = context(testEnabled, cancelEnabled, "test_gsk_mock_only", profiles)) {
            context.refresh();
            assertThat(context.getBeansOfType(TossCancellationGateway.class)).hasSize(expected ? 1 : 0);
        }
    }

    private static AnnotationConfigApplicationContext context(boolean testEnabled, boolean cancelEnabled,
                                                               String testSecret, String... profiles) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profiles);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-only", Map.of(
                "toss.payments.test.enabled", testEnabled,
                "toss.payments.test.full-cancel-enabled", cancelEnabled,
                "TOSS_PAYMENTS_SECRET_KEY", testSecret)));
        context.register(TossPaymentsTestConfiguration.class);
        return context;
    }
}
