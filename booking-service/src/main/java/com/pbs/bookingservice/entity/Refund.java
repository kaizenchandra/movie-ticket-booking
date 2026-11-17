package com.pbs.bookingservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
public class Refund {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private Long paymentId;
    @Column(nullable = false)
    private Long bookingId;
    @Column(nullable = false)
    private BigDecimal amount;
    @Column(nullable = false)
    private String currency = "INR";
    @Column(nullable = false)
    private String status = "SUCCEEDED";
    @Column(nullable = false)
    private String providerReference;
    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
