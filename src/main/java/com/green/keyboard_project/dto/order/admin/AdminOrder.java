package com.green.keyboard_project.dto.order.admin;

import java.time.LocalDateTime;
import java.util.List;

public record AdminOrder(
        int id, String number, LocalDateTime paidAt, Integer amount,
        String orderStatus, String paymentStatus, String deliveryStatus,
        String company, String tracking, String name, String phone,
        String postalCode, String roadAddress, String detailAddress,
        String memo, List<Item> items
) {
    public record Item(String name, int quantity) {}
}
