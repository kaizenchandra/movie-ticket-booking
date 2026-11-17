package com.pbs.bookingservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payment", uniqueConstraints = @UniqueConstraint(columnNames = {"booking_id", "idempotency_key"}))
@Getter
@Setter
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String gatewayOrderId;
    @Column(unique = true)
    private String gatewayPaymentId;
    @Column(nullable = false)
    private Long bookingId;
    @Column(nullable = false, length = 100)
    private String idempotencyKey;
    @Column(nullable = false)
    private String paymentToken;
    @Column(nullable = false)
    private BigDecimal amount;
    @Column(nullable = false)
    private String currency = "INR";
    @Column(nullable = false)
    private String status;
    @Column(nullable = false)
    private String providerReference;
    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
