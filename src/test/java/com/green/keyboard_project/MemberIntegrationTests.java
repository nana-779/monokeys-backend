package com.green.keyboard_project;

import com.green.keyboard_project.dto.*;
import com.green.keyboard_project.entity.User;
import com.green.keyboard_project.exceptions.*;
import com.green.keyboard_project.repository.UserRepository;
import com.green.keyboard_project.security.WithdrawalAuth;
import com.green.keyboard_project.service.UserService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** H2 격리 환경에서 회원 API를 검증합니다. 로그인 통합 기반이 필요합니다. */
@SpringBootTest
@Transactional
class MemberIntegrationTests {
    @Autowired UserService service;
    @Autowired PasswordEncoder passwords;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    @MockitoBean WithdrawalAuth auth;
    MockMvc secured;
    String email;
    final String password = "Keyboard123";

    @BeforeEach void setup() {
        email = "t-" + UUID.randomUUID().toString().substring(0, 12) + "@example.com";
        secured = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class)).build();
    }
    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    // 가입 운영 코드에 의존하지 않는 탈퇴 테스트 전용 회원입니다.
    int createMember() {
        var user = new User();
        user.setEmail(email);
        user.setPassword(passwords.encode(password));
        user.setName("테스트 회원");
        user.setPhoneNumber("01012345678");
        user.setRole(User.RoleType.USER);
        user.setCreatedAt(java.time.LocalDateTime.now());
        user.setUpdatedAt(user.getCreatedAt());
        return users.saveAndFlush(user).getUserId();
    }

    @Test void connectsOnlyToIsolatedTestDatabase() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualToIgnoringCase("keyboard_test");
        assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("create-drop");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='PUBLIC'", Integer.class)).isEqualTo(19);
    }


    @Test void preservesCredentialedCorsForMemberPathsOnly() throws Exception {
        secured.perform(options("/api/users/me").header("Origin","http://localhost:3000")
            .header("Access-Control-Request-Method","DELETE")
            .header("Access-Control-Request-Headers","content-type,x-csrf-token"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Credentials","true"))
            .andExpect(header().string("Access-Control-Allow-Origin","http://localhost:3000"));
        secured.perform(options("/api/users/me").header("Origin","https://untrusted.example")
            .header("Access-Control-Request-Method","DELETE")).andExpect(status().isForbidden());
    }

    @Test void withdrawalRoutesAreHandledByUserControllerAfterConsolidation() throws Exception {
        var mappings = context.getBean(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class)
            .getHandlerMethods();
        for (String path : List.of("/api/users/me/verify-password", "/api/users/me")) {
            var matches = mappings.entrySet().stream()
                .filter(entry -> entry.getKey().getPatternValues().contains(path)).toList();
            assertThat(matches).hasSize(1);
            assertThat(matches.getFirst().getValue().getBeanType())
                .isEqualTo(com.green.keyboard_project.controller.UserController.class);
        }
        var controllerOnly = MockMvcBuilders.webAppContextSetup(context).build();
        controllerOnly.perform(delete("/api/users/me").contentType("application/json").content("{"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        controllerOnly.perform(post("/api/users/me/verify-password").contentType("application/json").content("{"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
    }
    @Test void blocksAnonymousAndUnverifiedCookieAuthentication() throws Exception {
        for (var request : List.of(post("/api/users/me/verify-password"),delete("/api/users/me"))) {
            secured.perform(request.contentType("application/json").content("{\"password\":\"Keyboard123\"}"))
                .andExpect(status().isUnauthorized()); // 로그인 JWT 체인에서 미인증 요청 거부
        }
        secured.perform(delete("/api/users/me").cookie(new Cookie("accessToken","unverified"))
            .contentType("application/json").content("{\"password\":\"Keyboard123\"}"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(auth);
    }

    @Test void csrfHeaderDoesNotReplaceVerifiedIdentityOrEnableBearerFallback() throws Exception {
        var session = new MockHttpSession();
        var token = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "test-csrf");
        session.setAttribute("org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository.CSRF_TOKEN", token);
        var tokenRequest = new MockHttpServletRequest();
        new XorCsrfTokenRequestAttributeHandler().handle(tokenRequest, new MockHttpServletResponse(), () -> token);
        String encoded = ((CsrfToken) tokenRequest.getAttribute(CsrfToken.class.getName())).getToken();
        secured.perform(delete("/api/users/me").session(session).header("X-CSRF-TOKEN",encoded)
            .cookie(new Cookie("accessToken","unverified"))
            .header("Authorization","Bearer unverified.token")
            .contentType("application/json").content("{\"password\":\"Keyboard123\"}"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(auth);
    }

    @Test void authenticatedControllerWithdrawsAndRejectsDeletedAccount() throws Exception {
        int id = createMember();
        given(auth.isPasswordMember(id)).willReturn(true);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            new WithdrawalAuth.Principal(id),null,List.of()));
        var controllerOnly = MockMvcBuilders.webAppContextSetup(context).build();
        controllerOnly.perform(delete("/api/users/me").contentType("application/json")
            .content("{\"password\":\"Keyboard123\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        assertThat(users.findById(id).orElseThrow().isDeleted()).isTrue();
        verify(auth).revokeRefreshTokens(id);
        assertThatThrownBy(() -> service.verifyPassword(id,password)).isInstanceOf(UnauthenticatedException.class);
    }

    @Test void passwordRecheckAndSoftDeleteRetainMemberRowAndInvokeRevocation() {
        int id = createMember();
        given(auth.isPasswordMember(id)).willReturn(true);
        assertThat(service.verifyPassword(id,"Wrong123")).isFalse();
        assertThat(service.verifyPassword(id,password)).isTrue();
        service.withdraw(id,password);
        jdbc.execute("SELECT 1");
        User user = users.findById(id).orElseThrow();
        assertThat(user.isDeleted()).isTrue();
        assertThat(user.getDeletedAt()).isNotNull();
        assertThat(user.getEmail()).hasSize(50).isNotEqualTo(email);
        assertThat(user.getName()).isEqualTo("탈퇴 회원");
        assertThat(user.getPhoneNumber()).isNull();
        assertThat(passwords.matches(password,user.getPassword())).isFalse();
        users.flush();
        assertThat(jdbc.queryForObject("SELECT is_deleted FROM users WHERE user_id=?",Integer.class,id)).isEqualTo(1);
        verify(auth).revokeRefreshTokens(id);
    }

    @Test void authenticatedControllerUsesPrincipalRatherThanSubmittedUserId() throws Exception {
        int id = createMember();
        given(auth.isPasswordMember(id)).willReturn(true);
        // 컨트롤러 테스트에서는 필터를 제외하고 인증된 principal을 직접 주입합니다.
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            new WithdrawalAuth.Principal(id),null,List.of()));
        var controllerOnly = MockMvcBuilders.webAppContextSetup(context).build();
        controllerOnly.perform(post("/api/users/me/verify-password").contentType("application/json")
            .content("{\"password\":\"Keyboard123\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.verified").value(true));
        controllerOnly.perform(delete("/api/users/me").contentType("application/json")
            .content("{\"password\":\"Wrong123\"}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.success").value(false));
    }

    @Test void revocationFailureRollsBackAccountChanges() {
        int id = createMember();
        given(auth.isPasswordMember(id)).willReturn(true);
        doThrow(new IllegalStateException("revocation unavailable")).when(auth).revokeRefreshTokens(id);
        assertThatThrownBy(() -> service.withdraw(id,password)).isInstanceOf(IllegalStateException.class);
        // 테스트 회원 생성과 탈퇴가 같은 트랜잭션에 있으므로 전체 롤백 후 회원이 남지 않아야 합니다.
        TestTransaction.end();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE user_id=?",Integer.class,id)).isZero();
    }
}
