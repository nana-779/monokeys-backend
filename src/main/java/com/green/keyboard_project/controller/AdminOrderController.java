package com.green.keyboard_project.controller;

import com.green.keyboard_project.dto.ApiResponse;
import com.green.keyboard_project.service.AdminOrderService;
import com.green.keyboard_project.dto.order.admin.AdminOrder;
import com.green.keyboard_project.dto.order.admin.AdminOrderEdit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController {
    private final AdminOrderService service;

    public record ShipmentRequest(@NotBlank @Size(max = 50) String company,
                                  @NotBlank @Size(max = 100) String tracking) {}

    @GetMapping
    public ApiResponse<List<AdminOrder>> getAdminOrders() {
        return ApiResponse.ok(service.getAdminOrders());
    }

    @PatchMapping("/{orderId}")
    public ApiResponse<Void> updateAdminOrder(@PathVariable int orderId, @Valid @RequestBody AdminOrderEdit edit) {
        service.updateAdminOrder(orderId, edit);
        return ApiResponse.ok();
    }

    @PatchMapping("/{orderId}/shipment")
    public ApiResponse<Void> registerAdminShipment(@PathVariable int orderId, @Valid @RequestBody ShipmentRequest request) {
        service.registerAdminShipment(orderId, request.company(), request.tracking());
        return ApiResponse.ok();
    }
}
