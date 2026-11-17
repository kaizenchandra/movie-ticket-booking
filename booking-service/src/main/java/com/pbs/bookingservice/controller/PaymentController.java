package com.pbs.bookingservice.controller;

import com.pbs.bookingservice.common.resp.BookingResponse;
import com.pbs.bookingservice.entity.Payment;
import com.pbs.bookingservice.entity.Refund;
import com.pbs.bookingservice.service.BookingService;
import com.pbs.bookingservice.service.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/booking/{id}")
@RequiredArgsConstructor
public class PaymentController {
    private final BookingService bookings;
    private final CurrentUser users;

    @PostMapping("/payments")
    public Payment pay(@PathVariable Long id, @RequestHeader("Idempotency-Key") String key,
                       @Valid @RequestBody PaymentRequest request, Authentication user) {
        return bookings.pay(id, users.id(user), key, request.paymentToken());
    }

    @GetMapping("/payments")
    public List<Payment> payments(@PathVariable Long id, Authentication user) {
        return bookings.getPayments(id, users.id(user));
    }

    @PutMapping("/cancel")
    public BookingResponse cancel(@PathVariable Long id, Authentication user) {
        return bookings.cancel(id, users.id(user));
    }

    @PostMapping("/refunds")
    public Refund refund(@PathVariable Long id, Authentication user) {
        return bookings.refund(id, users.id(user));
    }

    @GetMapping("/refunds")
    public Refund getRefund(@PathVariable Long id, Authentication user) {
        return bookings.getRefund(id, users.id(user));
    }

    public record PaymentRequest(@NotBlank String paymentToken) {
    }
}
