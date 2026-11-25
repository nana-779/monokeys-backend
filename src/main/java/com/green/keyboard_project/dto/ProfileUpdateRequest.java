package com.green.keyboard_project.dto;

import static com.green.keyboard_project.dto.SignupRequest.PHONE_FORMAT_MESSAGE;
import static com.green.keyboard_project.dto.SignupRequest.PHONE_PATTERN;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 회원정보 수정 요청입니다. 회원가입과 같은 입력 규칙을 씁니다.
 * 이메일·비밀번호·회원 ID·주소 ID는 받지 않습니다. 대상 회원은 로그인 정보로만 정합니다.
 */
public record ProfileUpdateRequest(
    @NotBlank(message = "이름을 입력해 주세요.")
    @Size(max = 50, message = "이름은 50자 이하로 입력해 주세요.") String name,
    @NotBlank(message = "연락처를 입력해 주세요.")
    @Pattern(regexp = PHONE_PATTERN, message = PHONE_FORMAT_MESSAGE) String phoneNumber,
    @NotBlank(message = "우편번호를 입력해 주세요.")
    @Pattern(regexp = "[0-9]{5}", message = "우편번호는 숫자 5자리로 입력해 주세요.") String postalCode,
    @NotBlank(message = "도로명 주소를 입력해 주세요.")
    @Size(max = 255, message = "도로명 주소는 255자 이하로 입력해 주세요.") String address,
    @NotBlank(message = "상세 주소를 입력해 주세요.")
    @Size(max = 255, message = "상세 주소는 255자 이하로 입력해 주세요.") String detailAddress
) {
}
