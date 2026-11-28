package com.green.keyboard_project.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "refund", uniqueConstraints = @UniqueConstraint(
        name = "uq_refund_pg_cancel_tid",
        columnNames = "pg_cancel_tid"
))
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "refund_id", nullable = false)
    private int refundId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Column(name = "amount", nullable = false)
    private int amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type")
    private RequestType requestType;

    @Enumerated(EnumType.STRING)
    @Column(name = "return_reason_type")
    private ReturnReasonType returnReasonType;

    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RefundStatusType status;

    @Column(name = "rejection_reason", length = 255)
    private String rejectionReason;

    @Column(name = "pg_cancel_tid", length = 200)
    private String pgCancelTid;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    public enum RequestType {
        CANCELLATION,
        RETURN
    }

    public enum ReturnReasonType {
        CHANGE_OF_MIND,
        DEFECT,
        WRONG_ITEM
    }

    public enum RefundStatusType {
        REQUESTED,
        COMPLETED,
        REJECTED,
        PENDING_APPROVAL,
        WITHDRAWN,
        AWAITING_RETURN
    }
}