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










}
