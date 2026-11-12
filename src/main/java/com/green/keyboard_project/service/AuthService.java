package com.green.keyboard_project.service;

import com.green.keyboard_project.entity.RefreshToken;
import com.green.keyboard_project.entity.User;
import com.green.keyboard_project.exceptions.UnauthenticatedException;
import com.green.keyboard_project.repository.RefreshTokenRepository;
import com.green.keyboard_project.repository.UserRepository;
import com.green.keyboard_project.security.JwtTokenProvider;
import com.green.keyboard_project.security.TokenHasher;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuthService {

    // 이메일 없음·비밀번호 불일치·탈퇴 회원 모두 같은 메시지 (어떤 이유로 실패했는지 노출하지 않음)
    public static final String LOGIN_FAILED = "이메일 또는 비밀번호가 올바르지 않습니다.";
    public static final String LOGIN_REQUIRED = "로그인이 필요합니다.";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public record LoginResult(User user, String accessToken, String refreshToken) {
    }

    // AUTH-003 로그인: 회원 조회 → BCrypt 비교 → 탈퇴 여부 확인 → 토큰 발급
    @Transactional
    public LoginResult login(String email, String password) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UnauthenticatedException(LOGIN_FAILED));
        if (!passwordEncoder.matches(password, user.getPassword()) || user.isDeleted()) {
            throw new UnauthenticatedException(LOGIN_FAILED);
        }

        String accessToken = jwtTokenProvider.createAccessToken(user.getUserId(), user.getRole());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getUserId());

        // 회원당 1개: 기존 토큰 삭제 후 새 토큰 해시 저장
        refreshTokenRepository.deleteByUser(user);
        refreshTokenRepository.flush();

        LocalDateTime now = LocalDateTime.now();
        RefreshToken saved = new RefreshToken();
        saved.setUser(user);
        saved.setTokenHash(TokenHasher.sha256(refreshToken));
        saved.setExpiresAt(now.plusNanos(jwtTokenProvider.getRefreshExpirationMs() * 1_000_000));
        saved.setCreatedAt(now);
        refreshTokenRepository.save(saved);

        return new LoginResult(user, accessToken, refreshToken);
    }

    // 로그인한 사용자 조회 (/me)
    @Transactional(readOnly = true)
    public User getActiveUser(int userId) {
        return userRepository.findById(userId)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new UnauthenticatedException(LOGIN_REQUIRED));
    }

    // AUTH-004 로그아웃: 쿠키의 Refresh Token 해시와 같은 행 삭제
    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) return;
        refreshTokenRepository.deleteByTokenHash(TokenHasher.sha256(refreshToken));
    }

    // AUTH-005 재발급: 서명·만료 → DB 해시 일치 → 탈퇴 여부 확인 → 새 Access Token
    @Transactional(readOnly = true)
    public String refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) throw new UnauthenticatedException(LOGIN_REQUIRED);

        Claims claims;
        try {
            claims = jwtTokenProvider.parse(refreshToken);
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthenticatedException(LOGIN_REQUIRED);
        }
        if (!JwtTokenProvider.TYPE_REFRESH.equals(claims.get("type", String.class))) {
            throw new UnauthenticatedException(LOGIN_REQUIRED);
        }

        RefreshToken saved = refreshTokenRepository.findByTokenHash(TokenHasher.sha256(refreshToken))
                .orElseThrow(() -> new UnauthenticatedException(LOGIN_REQUIRED));
        if (saved.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new UnauthenticatedException(LOGIN_REQUIRED);
        }

        User user = getActiveUser(saved.getUser().getUserId());
        return jwtTokenProvider.createAccessToken(user.getUserId(), user.getRole());
    }
}
