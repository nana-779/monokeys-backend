package com.green.keyboard_project.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.cors.CorsConfiguration;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import static org.springframework.security.config.Customizer.withDefaults;

@Configuration(proxyBeanMethods = false)
public class MemberSecurityConfig {
    @Bean
    @Order(1)
    SecurityFilterChain memberSecurity(HttpSecurity http,
        @Value("${keyboard-shop.frontend-origin:http://localhost:3000}") String origin) throws Exception {
        var paths = PathPatternRequestMatcher.withDefaults();
        var signup = paths.matcher(HttpMethod.POST, "/api/users/signup");
        var email = paths.matcher(HttpMethod.GET, "/api/auth/check-email");
        return http.securityMatcher(new OrRequestMatcher(
                paths.matcher("/api/users/signup"), paths.matcher("/api/auth/check-email")))
            .cors(cors -> cors.configurationSource(request -> {
                var policy = new CorsConfiguration();
                policy.setAllowedOrigins(List.of(origin));
                policy.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
                policy.setAllowedHeaders(List.of("Accept", "Content-Type", "X-CSRF-TOKEN"));
                policy.setAllowCredentials(true);
                return policy;
            }))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // 인증이 없는 가입만 CSRF 검사에서 제외합니다. 쿠키 인증 탈퇴에는 CSRF 보호를 유지합니다.
            .csrf(csrf -> csrf.ignoringRequestMatchers(signup))
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .authorizeHttpRequests(a -> a.requestMatchers(signup, email).permitAll()
                .anyRequest().authenticated())
            .build();
    }
    // 사용자 정의 체인 추가로 자동 기본 체인이 사라지므로, 기존 기본 보호를 다른 경로에 유지합니다.
    @Bean
    @Order(2)
    SecurityFilterChain existingDefaultSecurity(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(a -> a.anyRequest().authenticated())
            .formLogin(withDefaults()).httpBasic(withDefaults()).build();
    }
}
