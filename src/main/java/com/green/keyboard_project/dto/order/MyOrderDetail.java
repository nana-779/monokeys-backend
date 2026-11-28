package com.green.keyboard_project.dto.order;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

public record MyOrderDetail(@JsonUnwrapped MyOrderSummary summary,
                            String ordererName, String ordererPhone,
                            String postalCode, String roadAddress, String detailAddress, boolean receiptConfirmed,
                            String trackingCompany, String trackingNumber) {}
