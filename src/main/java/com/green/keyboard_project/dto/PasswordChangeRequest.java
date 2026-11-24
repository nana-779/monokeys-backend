package com.green.keyboard_project.dto;

import static com.green.keyboard_project.dto.SignupRequest.PASSWORD_POLICY_MESSAGE;
import static com.green.keyboard_project.dto.SignupRequest.meetsPasswordPolicy;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;

/**
 * 비밀번호 변경 요청입니다. 새 비밀번호는 회원가입과 같은 규칙을 씁니다.
 * 새 비밀번호 확인값 비교는 화면에서만 하고 서버로 보내지 않습니다.
 */
public record PasswordChangeRequest(
    @NotBlank(message = "현재 비밀번호를 입력해 주세요.") String currentPassword,
    @NotBlank(message = "새 비밀번호를 입력해 주세요.") String newPassword
) {

    // 현재 비밀번호는 BCrypt 한도(72바이트)만 확인합니다. 빈 값은 @NotBlank에서 처리합니다.
    @AssertTrue(message = "비밀번호 길이를 확인해 주세요.")
    public boolean isCurrentPasswordLengthValid() {
        return currentPassword == null || currentPassword.isBlank()
            || currentPassword.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    @AssertTrue(message = PASSWORD_POLICY_MESSAGE)
    public boolean isNewPasswordPolicyValid() {
        if (newPassword == null || newPassword.isBlank()) {
            return true;
        }
        return meetsPasswordPolicy(newPassword);
    }

    @AssertTrue(message = "현재와 다른 비밀번호를 입력해 주세요.")
    public boolean isNewPasswordDifferent() {
        return currentPassword == null || newPassword == null || !currentPassword.equals(newPassword);
    }
}
