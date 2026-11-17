package com.pbs.bookingservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbs.bookingservice.entity.Payment;
import com.pbs.bookingservice.repository.RazorpayWebhookRepository;
import com.pbs.bookingservice.service.CurrentUser;
import com.pbs.bookingservice.service.RazorpayClient;
import com.pbs.bookingservice.service.RazorpayService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "razorpay.enabled", havingValue = "true")
public class RazorpayController {
    private final RazorpayService gateway;
    private final RazorpayClient client;
    private final CurrentUser users;
    private final RazorpayWebhookRepository inbox;
    private final ObjectMapper mapper;

    @PostMapping("/api/v1/booking/{id}/razorpay/order")
    public Map<String, Object> order(@PathVariable Long id, @RequestHeader("Idempotency-Key") String key, Authentication user) {
        return gateway.order(id, users.id(user), key);
    }

    @PostMapping("/api/v1/booking/{id}/razorpay/verify")
    public Payment verify(@PathVariable Long id, @Valid @RequestBody Verification data, Authentication user) {
        return gateway.verify(id, users.id(user), data.orderId(), data.paymentId(), data.signature());
    }

    @PostMapping("/api/v1/webhooks/razorpay")
    @Transactional
    public void webhook(@RequestBody byte[] body, @RequestHeader("X-Razorpay-Signature") String signature) throws Exception {
        client.verifyWebhook(body, signature);
        mapper.readTree(body); // Persist valid JSON only; verify the exact raw bytes first.
        String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        inbox.insert(id, new String(body, StandardCharsets.UTF_8));
    }

    public record Verification(@NotBlank String orderId, @NotBlank String paymentId, @NotBlank String signature) {
    }
}
