package com.green.keyboard_project.config;

import com.green.keyboard_project.security.JwtAuthenticationFilter;
import com.green.keyboard_project.security.JwtTokenProvider;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtTokenProvider jwtTokenProvider) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)            // 쿠키 SameSite=Lax로 CSRF 대응
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/api/auth/logout", "/api/auth/refresh").permitAll()
                        .requestMatchers("/api/users/signup", "/api/auth/check-email").permitAll()
                        // 신규 회원이 로그인 전에 인증번호를 발급하고 검증할 수 있습니다.
                        .requestMatchers(HttpMethod.POST, "/api/auth/email/send", "/api/auth/email/verify").permitAll()
                        .requestMatchers("/api/auth/me").authenticated()
                        // 회원정보 수정·비밀번호 변경·비밀번호 재확인·회원탈퇴는 로그인한 회원만 접근합니다.
                        .requestMatchers("/api/users/me", "/api/users/me/**").authenticated()
                        // 전체 주문 목록 조회는 관리자(ADMIN)만 접근 가능
                        .requestMatchers(HttpMethod.GET, "/api/orders").hasRole("ADMIN")
                        // 주문 생성 및 단건 조회, 마이페이지 주문은 인증된 회원만 접근 가능
                        .requestMatchers(HttpMethod.POST, "/api/orders").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/orders/{orderId}", "/api/orders/number/{orderNumber}").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/orders/me/{orderNumber}/receipt").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/orders/me", "/api/orders/me/{orderNumber}").authenticated()
                        // 장바구니 및 커스텀 키캡 업로드/삭제 API 보호
                        .requestMatchers("/api/cart/**", "/api/custom-keycaps/**").authenticated()
                        // 결제 승인 및 실패/취소 통지 API 보호
                        .requestMatchers(HttpMethod.POST, "/api/payments/complete", "/api/payments/fail").authenticated()
                        // 환불 관련 API 보호
                        .requestMatchers("/api/payments/orders/{orderNumber}/refund/eligibility").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/payments/orders/{orderNumber}/refund/history").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/payments/orders/{orderNumber}/refund/cancel",
                                "/api/payments/orders/{orderNumber}/refund/reconcile").authenticated()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/categories/**", "/api/products/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-resources/**").permitAll()
                        .anyRequest().permitAll()
                )
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeError(res, 401, "로그인이 필요합니다."))
                        .accessDeniedHandler((req, res, ex) -> writeError(res, 403, "접근 권한이 없습니다.")))
                .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    // 프론트(3000)에서 쿠키를 포함한 요청 허용
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${cors.allowed-origin:http://localhost:3000}") String allowedOrigin) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of(allowedOrigin, "http://127.0.0.1:3000"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // 다른 출처의 가입 화면에서도 429 응답에 담긴 서버의 재요청 대기 시간을 읽을 수 있게 합니다.
        config.addExposedHeader(HttpHeaders.RETRY_AFTER);
        config.setAllowCredentials(true); // 쿠키 전달 허용 (허용 주소에 * 사용 불가)
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    // Security 단계 오류도 팀 ApiResponse 형식({ success, message })으로 응답
    private static void writeError(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write("{\"success\":false,\"message\":\"" + message + "\",\"data\":null}");
    }
}
