package com.green.keyboard_project;

import com.green.keyboard_project.dto.*;
import com.green.keyboard_project.entity.User;
import com.green.keyboard_project.exceptions.*;
import com.green.keyboard_project.repository.UserRepository;
import com.green.keyboard_project.service.UserService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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

/** 실제 keyboard_shop에 연결하되 각 테스트의 데이터는 롤백합니다. */
@SpringBootTest
@Transactional
class MemberIntegrationTests {
    @Autowired UserService service;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    MockMvc secured;
    String email;
    final String password = "Keyboard123";

    @BeforeEach void setup() {
        email = "t-" + UUID.randomUUID().toString().substring(0, 12) + "@example.com";
        secured = MockMvcBuilders.webAppContextSetup(context)
            .build();
    }
    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    SignupRequest request(String candidate, String pass, AddressRequest address) {
        var mapper = JsonMapper.builder().build();
        return new SignupRequest(candidate, pass, "테스트 회원", "01012345678", address,
            new TermsConsent(mapper.readTree("true"), mapper.readTree("true")));
    }
    SignupRequest valid() {
        return request(email, password, new AddressRequest("04524", "서울 세종대로", "101호"));
    }
    String json() {
        return """
            {"email":"%s","password":"Keyboard123","name":"테스트 회원",
             "phoneNumber":"01012345678","defaultAddress":{"zonecode":"04524",
             "roadAddress":"서울 세종대로","detailAddress":"101호"},
             "termsConsent":{"serviceTerms":true,"privacyPolicy":true}}
            """.formatted(email);
    }

    @Test void connectsToExpectedDatabaseWithoutAutomaticDdl() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("keyboard_shop");
        assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("none");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='keyboard_shop'", Integer.class)).isEqualTo(19);
    }

    @Test void validatesDatabaseLengthsPhoneAndPasswordByteLimit() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(valid())).isEmpty();
            for (String pass : List.of("12345678", "abcdefgh", "short1", "a1" + "가".repeat(25))) {
                assertThat(validator.validate(request(email, pass, valid().defaultAddress()))).isNotEmpty();
            }
            assertThat(validator.validate(request("a".repeat(45)+"@a.com", password, valid().defaultAddress()))).isNotEmpty();
            var bad = new SignupRequest(email,password,"회원","01112345678",valid().defaultAddress(),valid().termsConsent());
            assertThat(validator.validate(bad)).isNotEmpty();
            var nonBoolean = new TermsConsent(JsonMapper.builder().build().readTree("\"true\""), valid().termsConsent().privacyPolicy());
            assertThat(validator.validate(nonBoolean)).isNotEmpty();
        }
    }

    @Test void validatesSignupAndRejectsDuplicateEmail() throws Exception {
        secured.perform(get("/api/auth/check-email").param("email", email))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.available").value(true));
    }

    @Test void signupEndpointSavesHashedPasswordAndDefaultAddress() throws Exception {
        service.signup(valid());
        String hash = jdbc.queryForObject("SELECT password FROM users WHERE email=?", String.class, email);
        assertThat(new BCryptPasswordEncoder().matches(password, hash)).isTrue();
        var address = jdbc.queryForMap("SELECT a.* FROM address a JOIN users u ON a.user_id=u.user_id WHERE u.email=?", email);
        assertThat(address.get("postal_code")).isEqualTo("04524");
        assertThat(address.get("address_alias")).isEqualTo("기본");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM address a JOIN users u ON a.user_id=u.user_id WHERE u.email=? AND a.is_default=1", Integer.class, email)).isEqualTo(1);
    }

    @Test void addressFailureRollsBackBothUserAndAddress() {
        assertThatThrownBy(() -> service.signup(request(email,password,
            new AddressRequest("123456", "도로", "상세")))).isInstanceOf(RuntimeException.class);
        TestTransaction.end();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email=?",Integer.class,email)).isZero();
    }
}
