package com.green.keyboard_project.dto.order.admin;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminOrderEdit(
        @Size(max = 50) @Pattern(regexp = "(?s).*\\S.*") String name,
        @Size(max = 11) @Pattern(regexp = "(?s).*\\S.*") String phone,
        @Size(max = 10) @Pattern(regexp = "(?s).*\\S.*") String postalCode,
        @Size(max = 255) @Pattern(regexp = "(?s).*\\S.*") String roadAddress,
        @Size(max = 255) @Pattern(regexp = "(?s).*\\S.*") String detailAddress,
        @Size(max = 50) @Pattern(regexp = "(?s).*\\S.*") String company,
        @Size(max = 100) @Pattern(regexp = "(?s).*\\S.*") String tracking) {}
