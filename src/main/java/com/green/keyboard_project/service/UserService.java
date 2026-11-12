package com.green.keyboard_project.service;

import com.green.keyboard_project.entity.User;
import com.green.keyboard_project.exceptions.*;
import com.green.keyboard_project.repository.UserRepository;
import com.green.keyboard_project.security.WithdrawalAuth;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private final UserRepository users;
    private final ObjectProvider<WithdrawalAuth> authentication;
    private final PasswordEncoder passwords = new BCryptPasswordEncoder();

    public UserService(UserRepository users, ObjectProvider<WithdrawalAuth> authentication) {
        this.users = users;
        this.authentication = authentication;
    }



    @Transactional(readOnly = true)
    public boolean verifyPassword(int userId, String password) {
        User user = users.findById(userId).orElseThrow(UserService::inactive);
        requirePasswordMember(user);
        return passwords.matches(password, user.getPassword());
    }



    private WithdrawalAuth requirePasswordMember(User user) {
        if (user.isDeleted()) { throw inactive(); }
        WithdrawalAuth auth = authentication.getIfAvailable();
        if (auth == null) { throw new InvalidStateException("회원 인증 연동을 사용할 수 없습니다."); }
        if (!auth.isPasswordMember(user.getUserId())) {
            throw new AuthorizationFailureException("계정에 등록된 인증 수단으로 본인을 확인해 주세요.");
        }
        return auth;
    }

    private static UnauthenticatedException inactive() {
        return new UnauthenticatedException("로그인 정보를 다시 확인해 주세요.");
    }
}
