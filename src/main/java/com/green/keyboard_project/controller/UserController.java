package com.green.keyboard_project.controller;

import com.green.keyboard_project.dto.*;
import com.green.keyboard_project.service.UserService;
import com.green.keyboard_project.exceptions.UnauthenticatedException;
import com.green.keyboard_project.security.WithdrawalAuth;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
public class UserController {
    private final UserService users;
    public UserController(UserService users) { this.users = users; }
    private int userId(WithdrawalAuth.Principal principal) {
        if (principal == null) { throw new UnauthenticatedException("로그인 정보를 다시 확인해 주세요."); }
        return principal.userId();
    }
    @PostMapping("/api/users/me/verify-password")
    public ApiResponse<WithdrawalPasswordResponse> verifyPassword(
        @AuthenticationPrincipal WithdrawalAuth.Principal principal,
        @Valid @RequestBody WithdrawalPasswordRequest request) {
        return ApiResponse.ok(new WithdrawalPasswordResponse(users.verifyPassword(userId(principal), request.password())));
    }

}
