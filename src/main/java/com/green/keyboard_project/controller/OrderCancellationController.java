package com.green.keyboard_project.controller;

import com.green.keyboard_project.dto.ApiResponse;
import com.green.keyboard_project.dto.payment.OrderCancellationRequest;
import com.green.keyboard_project.exceptions.PaymentConfirmationException;
import com.green.keyboard_project.service.payment.OrderCancellationService;
import com.green.keyboard_project.service.payment.OrderCancellationStore;
import com.green.keyboard_project.service.payment.OrderCancellationStore.WorkflowRequest;
import com.green.keyboard_project.service.payment.OrderCancellationStore.WorkflowView;
import com.green.keyboard_project.entity.Refund.RequestType;
import java.util.List;
import com.green.keyboard_project.service.payment.OrderCancellationService.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/** 회원 주문 취소 요청의 인증, 입력 형식과 오류 응답을 담당합니다. */
@RestController
public class OrderCancellationController {
    private final OrderCancellationService cancellations;
    private final String allowedOrigin;
    private final OrderCancellationStore workflow;

    public OrderCancellationController(OrderCancellationService cancellations, OrderCancellationStore workflow,
            @Value("${cors.allowed-origin:http://localhost:3000}") String allowedOrigin) {
        this.cancellations = cancellations;
        this.workflow = workflow;
        this.allowedOrigin = allowedOrigin;
    }

    @PostMapping(path = "/api/payments/orders/{orderNumber}/refund/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<WorkflowView>> cancel(@PathVariable("orderNumber") String orderNumber,
            @AuthenticationPrincipal Integer memberId, @RequestHeader(name = "Origin", required = false) String origin,
            @RequestBody JsonNode body) {
        verifyWriteRequest(memberId, origin);
        var request = OrderCancellationRequest.fromJson(body);
        return ResponseEntity.accepted().body(ApiResponse.ok(workflow.requestWorkflow(orderNumber, memberId,
                new WorkflowRequest(RequestType.CANCELLATION, request.cancelReason(), null))));
    }

    @PostMapping("/api/payments/orders/{orderNumber}/refund/reconcile")
    public ResponseEntity<ApiResponse<Result>> reconcile(@PathVariable("orderNumber") String orderNumber,
            @AuthenticationPrincipal Integer memberId, @RequestHeader(name = "Origin", required = false) String origin) {
        verifyWriteRequest(memberId, origin);
        return response(cancellations.reconcile(orderNumber, memberId));
    }

    @GetMapping("/api/payments/orders/{orderNumber}/refund/request")
    public ResponseEntity<ApiResponse<WorkflowView>> workflow(@PathVariable("orderNumber") String orderNumber,
            @AuthenticationPrincipal Integer memberId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(workflow.workflow(orderNumber, memberId)));
    }

    @PostMapping("/api/payments/orders/{orderNumber}/refund/request")
    public ResponseEntity<ApiResponse<WorkflowView>> request(@PathVariable("orderNumber") String orderNumber,
            @AuthenticationPrincipal Integer memberId, @RequestHeader(name = "Origin", required = false) String origin,
            @RequestBody WorkflowRequest request) {
        verifyWriteRequest(memberId, origin);
        return ResponseEntity.accepted().body(ApiResponse.ok(workflow.requestWorkflow(orderNumber, memberId, request)));
    }

    @PostMapping("/api/payments/orders/{orderNumber}/refund/request/{id}/withdraw")
    public ResponseEntity<Void> withdraw(@PathVariable("orderNumber") String orderNumber, @PathVariable("id") int id,
            @AuthenticationPrincipal Integer memberId, @RequestHeader(name = "Origin", required = false) String origin) {
        verifyWriteRequest(memberId, origin);
        workflow.withdrawWorkflow(orderNumber, memberId, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/admin/refund-requests")
    public ResponseEntity<ApiResponse<List<WorkflowView>>> queue() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(workflow.workflowQueue()));
    }

    public record Review(String action, String reason) {}

    @PostMapping("/api/admin/refund-requests/{id}")
    public ResponseEntity<?> review(@PathVariable("id") int id, @AuthenticationPrincipal Integer adminId,
            @RequestHeader(name = "Origin", required = false) String origin, @RequestBody Review review) {
        verifyWriteRequest(adminId, origin);
        if (review.action() == null) throw new PaymentConfirmationException(400, "INVALID_ACTION", "처리 유형을 선택해 주세요.");
        return switch (review.action()) {
            case "APPROVE_CANCELLATION" -> response(cancellations.executeWorkflow(id, false));
            case "HOLD_RETURN" -> {
                workflow.holdReturn(id);
                yield ResponseEntity.noContent().build();
            }
            case "RECEIVE_RETURN" -> response(cancellations.executeWorkflow(id, true));
            case "RECONCILE" -> response(cancellations.reconcileWorkflow(id));
            case "REJECT" -> {
                workflow.rejectWorkflow(id, review.reason());
                yield ResponseEntity.noContent().build();
            }
            default -> throw new PaymentConfirmationException(400, "INVALID_ACTION", "처리 유형을 확인해 주세요.");
        };
    }

    private void verifyWriteRequest(Integer memberId, String origin) {
        if (memberId == null) throw new PaymentConfirmationException(401, "LOGIN_REQUIRED", "로그인이 필요합니다.");
        // 쿠키가 자동으로 전송되는 쓰기 요청은 기존 프론트 Origin과 일치할 때만 받습니다.
        if (!allowedOrigin.equals(origin)) {
            throw new PaymentConfirmationException(403, "CANCEL_ORIGIN_DENIED", "허용된 주문 화면에서 다시 요청해 주세요.");
        }
    }

    private static ResponseEntity<ApiResponse<Result>> response(Result result) {
        int status = "PENDING_CONFIRMATION".equals(result.status()) ? 202 : 200;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.ok(result));
    }

    public record ErrorDetail(String code) {}

    @ExceptionHandler(PaymentConfirmationException.class)
    public ResponseEntity<ApiResponse<ErrorDetail>> cancellationError(PaymentConfirmationException error) {
        var body = ApiResponse.<ErrorDetail>builder().success(false).message(error.getMessage())
                .data(new ErrorDetail(error.code())).build();
        return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore()).body(body);
    }

    // 잘못된 JSON이 계좌정보를 포함해도 원본 예외나 입력값을 오류 응답으로 내보내지 않습니다.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<ErrorDetail>> invalidJson() {
        return cancellationError(new PaymentConfirmationException(400, "INVALID_CANCEL_REQUEST", "취소 요청 형식을 확인해 주세요."));
    }
}
