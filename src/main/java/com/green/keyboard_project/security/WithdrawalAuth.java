package com.green.keyboard_project.security;

/** 로그인 담당 인증 계층이 구현할 계약. 쿠키/JWT 검증 구현체는 아직 연결되지 않았습니다. */
public interface WithdrawalAuth {
    record Principal(int userId) { }
    boolean isPasswordMember(int userId);
    /** 회원 탈퇴 트랜잭션 안에서 모든 Refresh Token을 무효화해야 합니다. */
    void revokeRefreshTokens(int userId);
}