package com.green.keyboard_project;

import com.green.keyboard_project.dto.ProfileUpdateRequest;
import com.green.keyboard_project.entity.Address;
import com.green.keyboard_project.entity.User;
import com.green.keyboard_project.repository.AddressRepository;
import com.green.keyboard_project.repository.UserRepository;
import com.green.keyboard_project.security.WithdrawalAuth;
import com.green.keyboard_project.service.UserService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** H2 격리 환경에서 회원정보 수정(AUTH-006)과 비밀번호 변경 API를 검증합니다. */
@SpringBootTest
@Transactional
class UserProfileIntegrationTests {
    @Autowired UserService service;
    @Autowired PasswordEncoder passwords;
    @Autowired UserRepository users;
    @Autowired AddressRepository addresses;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    @MockitoBean WithdrawalAuth auth;
    MockMvc secured;      // 보안 필터 포함 (비로그인 요청 확인용)
    MockMvc controller;   // 로그인 회원을 직접 넣어 컨트롤러만 확인
    final String password = "Keyboard123";
    final LocalDateTime oldTime = LocalDateTime.of(2026, 1, 1, 0, 0);

    @BeforeEach void setup() {
        secured = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(FilterChainProxy.class)).build();
        controller = MockMvcBuilders.webAppContextSetup(context).build();
    }
    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    // ===== 테스트 도우미 =====

    // 회원가입과 같은 모양으로 회원과 기본 배송지(수령인 = 회원 이름·전화번호)를 만듭니다.
    int createMember(boolean withDefaultAddress) {
        var user = new User();
        user.setEmail("p-" + UUID.randomUUID().toString().substring(0, 12) + "@example.com");
        user.setPassword(passwords.encode(password));
        user.setName("테스트 회원");
        user.setPhoneNumber("01012345678");
        user.setRole(User.RoleType.USER);
        user.setCreatedAt(oldTime);
        user.setUpdatedAt(oldTime);
        user = users.saveAndFlush(user);
        if (withDefaultAddress) {
            var address = new Address();
            address.setUser(user);
            address.setAddressAlias("기본");
            address.setRecipientName(user.getName());
            address.setRecipientPhone(user.getPhoneNumber());
            address.setPostalCode("04524");
            address.setRoadAddress("서울 세종대로");
            address.setDetailAddress("101호");
            address.setDefault(true);
            address.setCreatedAt(oldTime);
            addresses.saveAndFlush(address);
        }
        given(auth.isPasswordMember(user.getUserId())).willReturn(true);
        return user.getUserId();
    }

    // 로그인 JwtAuthenticationFilter와 같은 방식으로 회원 번호(Integer)를 principal로 넣습니다.
    void loginAs(int userId) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(userId, null, List.of()));
    }

    String profileJson(String phone) {
        return """
            {"name":"새 이름","phoneNumber":"%s","postalCode":"06236",
             "address":"서울 테헤란로","detailAddress":"202호"}
            """.formatted(phone);
    }

    // ===== 비로그인 =====

    @Test void rejectsAnonymousRequests() throws Exception {
        secured.perform(patch("/api/users/me").contentType("application/json").content(profileJson("01099998888")))
                .andExpect(status().isUnauthorized());
        secured.perform(patch("/api/users/me/password").contentType("application/json")
                        .content("{\"currentPassword\":\"Keyboard123\",\"newPassword\":\"Newpass123\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(auth);
    }

    // ===== 회원정보 수정 =====

    @Test void updatesUserAndDefaultAddressTogether() throws Exception {
        int id = createMember(true);
        loginAs(id);
        controller.perform(patch("/api/users/me").contentType("application/json").content(profileJson("01099998888")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("새 이름"))
                .andExpect(jsonPath("$.data.phoneNumber").value("01099998888"))
                .andExpect(jsonPath("$.data.roadAddress").value("서울 테헤란로"));
        users.flush();
        var user = jdbc.queryForMap("SELECT name, phone_number, updated_at FROM users WHERE user_id=?", id);
        assertThat(user.get("NAME")).isEqualTo("새 이름");
        assertThat(user.get("PHONE_NUMBER")).isEqualTo("01099998888");
        assertThat(user.get("UPDATED_AT").toString()).doesNotStartWith("2026-01-01");
        var address = jdbc.queryForMap("SELECT recipient_name, recipient_phone, postal_code, road_address, detail_address"
                + " FROM address WHERE user_id=? AND is_default=TRUE", id);
        assertThat(address.get("RECIPIENT_NAME")).isEqualTo("새 이름");
        assertThat(address.get("RECIPIENT_PHONE")).isEqualTo("01099998888");
        assertThat(address.get("POSTAL_CODE")).isEqualTo("06236");
        assertThat(address.get("ROAD_ADDRESS")).isEqualTo("서울 테헤란로");
        assertThat(address.get("DETAIL_ADDRESS")).isEqualTo("202호");
    }

    @Test void rejectsHyphenPhoneAndKeepsData() throws Exception {
        int id = createMember(true);
        loginAs(id);
        controller.perform(patch("/api/users/me").contentType("application/json").content(profileJson("010-9999-8888")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        assertThat(users.findById(id).orElseThrow().getPhoneNumber()).isEqualTo("01012345678");
    }

    @Test void usesPrincipalRatherThanSubmittedIds() throws Exception {
        int me = createMember(true);
        int other = createMember(true);
        loginAs(me);
        // 요청 본문에 다른 회원 번호·이메일을 넣어도 받는 칸이 없어 무시되고, 로그인한 회원만 바뀌어야 합니다.
        controller.perform(patch("/api/users/me").contentType("application/json").content("""
                    {"userId":%d,"addressId":1,"email":"hacker@example.com","name":"새 이름",
                     "phoneNumber":"01099998888","postalCode":"06236","address":"서울 테헤란로","detailAddress":"202호"}
                    """.formatted(other)))
                .andExpect(status().isOk());
        assertThat(users.findById(me).orElseThrow().getName()).isEqualTo("새 이름");
        User untouched = users.findById(other).orElseThrow();
        assertThat(untouched.getName()).isEqualTo("테스트 회원");
        assertThat(untouched.getEmail()).isNotEqualTo("hacker@example.com");
        assertThat(addresses.findByUser_UserIdAndIsDefaultTrue(other).orElseThrow().getRoadAddress())
                .isEqualTo("서울 세종대로");
    }

    @Test void keepsUpdatedAtWhenNothingChanged() {
        int id = createMember(true);
        service.updateProfile(id, new ProfileUpdateRequest("테스트 회원", "01012345678", "04524", "서울 세종대로", "101호"));
        assertThat(users.findById(id).orElseThrow().getUpdatedAt()).isEqualTo(oldTime);
    }

    @Test void returnsNotFoundWithoutDefaultAddress() throws Exception {
        int id = createMember(false);
        loginAs(id);
        controller.perform(patch("/api/users/me").contentType("application/json").content(profileJson("01099998888")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ===== 비밀번호 변경 =====

    @Test void changesPasswordRevokesTokensAndExpiresCookies() throws Exception {
        int id = createMember(true);
        loginAs(id);
        var response = controller.perform(patch("/api/users/me/password").contentType("application/json")
                        .content("{\"currentPassword\":\"Keyboard123\",\"newPassword\":\"Newpass123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse();
        assertThat(response.getHeaders("Set-Cookie"))
                .anySatisfy(cookie -> assertThat(cookie).startsWith("access_token=").contains("Max-Age=0"))
                .anySatisfy(cookie -> assertThat(cookie).startsWith("refresh_token=").contains("Max-Age=0"));
        String saved = users.findById(id).orElseThrow().getPassword();
        assertThat(passwords.matches("Newpass123", saved)).isTrue();
        assertThat(passwords.matches(password, saved)).isFalse();
        verify(auth).revokeRefreshTokens(id);
    }

    @Test void rejectsWrongCurrentPasswordWithoutChanges() throws Exception {
        int id = createMember(true);
        loginAs(id);
        controller.perform(patch("/api/users/me/password").contentType("application/json")
                        .content("{\"currentPassword\":\"Wrong123\",\"newPassword\":\"Newpass123\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
        assertThat(passwords.matches(password, users.findById(id).orElseThrow().getPassword())).isTrue();
        verify(auth, never()).revokeRefreshTokens(anyInt());
    }

    @Test void validatesNewPasswordPolicyAndSameValue() throws Exception {
        int id = createMember(true);
        loginAs(id);
        for (String body : List.of(
                "{\"currentPassword\":\"Keyboard123\",\"newPassword\":\"short1\"}",          // 8자 미만
                "{\"currentPassword\":\"Keyboard123\",\"newPassword\":\"onlyletters\"}",     // 숫자 없음
                "{\"currentPassword\":\"Keyboard123\",\"newPassword\":\"Keyboard123\"}")) {  // 현재와 같음
            controller.perform(patch("/api/users/me/password").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }
        assertThat(passwords.matches(password, users.findById(id).orElseThrow().getPassword())).isTrue();
        verify(auth, never()).revokeRefreshTokens(anyInt());
    }
}
