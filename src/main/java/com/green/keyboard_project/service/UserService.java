package com.green.keyboard_project.service;

import com.green.keyboard_project.dto.SignupRequest;
import com.green.keyboard_project.dto.SignupResponse;
import com.green.keyboard_project.entity.Address;
import com.green.keyboard_project.entity.User;
import com.green.keyboard_project.exceptions.*;
import com.green.keyboard_project.repository.AddressRepository;
import com.green.keyboard_project.repository.UserRepository;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private final UserRepository users;
    private final AddressRepository addresses;

    public UserService(UserRepository users, AddressRepository addresses) {
        this.users = users;
        this.addresses = addresses;
    }



    @Transactional(readOnly = true)
    public boolean isEmailAvailable(String email) { return !users.existsByEmail(email); }




}