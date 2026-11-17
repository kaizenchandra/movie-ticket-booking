package com.pbs.bookingservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.pbs.bookingservice.common.ex.BookingNotFoundException;
import com.pbs.bookingservice.common.ex.BookingValidationException;
import com.pbs.bookingservice.entity.Booking;
import com.pbs.bookingservice.entity.Payment;
import com.pbs.bookingservice.entity.Refund;
import com.pbs.bookingservice.entity.Show;
import com.pbs.bookingservice.entity.enums.BookingStatus;
import com.pbs.bookingservice.repository.BookingRepository;
import com.pbs.bookingservice.repository.PaymentRepository;
import com.pbs.bookingservice.repository.RefundRepository;
import com.pbs.bookingservice.repository.ShowRepository;
import com.pbs.bookingservice.saga.OutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "razorpay.enabled", havingValue = "true")
public class RazorpayService {
    private final jakarta.persistence.EntityManager entityManager;
    private final RazorpayClient client;
    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final BookingService bookingService;
    private final ShowRepository shows;
    private final OutboxService outbox;

    private Booking owned(Long id, Long user) {
        Booking b = bookings.findLockedById(id).orElseThrow(() -> new BookingNotFoundException("Booking not found"));
        if (!b.getUserId().equals(user)) throw new BookingNotFoundException("Booking not found");
        return b;
    }

    @Transactional
    public Map<String, Object> order(Long id, Long user, String key) {
        Booking b = owned(id, user);
        if (key == null || !key.matches("[!-~]{1,100}"))
            throw new BookingValidationException("Invalid Idempotency-Key");
        if (b.getStatus() != BookingStatus.PENDING_PAYMENT || !b.getExpiryTime().isAfter(LocalDateTime.now()))
            throw new BookingValidationException("Booking is not payable");
        Show show = shows.findById(b.getShowId()).orElseThrow();
        if (!"ACTIVE".equals(show.getStatus()) || !show.getShowTime().isAfter(LocalDateTime.now()))
            throw new BookingValidationException("Show is unavailable");
        var attempts = payments.findByBookingIdOrderById(id);
        // The booking lock serializes all gateways and callbacks. Never offer Checkout
        // after money has moved, even if a prior attempt came from another provider.
        if (attempts.stream().anyMatch(x -> List.of("SUCCEEDED", "REFUND_PENDING", "REFUNDED").contains(x.getStatus())))
            throw new BookingValidationException("Booking already has a settled payment");
        Payment p = attempts.stream().filter(x -> x.getGatewayOrderId() != null).findFirst().orElse(null);
        if (p != null && !"ORDER_CREATED".equals(p.getStatus()))
            throw new BookingValidationException("Payment is not payable");
        if (p == null) {
            if (payments.findByBookingIdAndIdempotencyKey(id, key).isPresent())
                throw new BookingValidationException("Key already used");
            long amount = b.getFinalAmount().movePointRight(2).longValueExact();
            JsonNode order = null;
            var candidates = client.call("GET", "/orders?receipt=" + java.net.URLEncoder.encode(b.getBookingReference(), java.nio.charset.StandardCharsets.UTF_8) + "&count=100", null, null).path("items");
            for (var candidate : candidates) {
                if (b.getBookingReference().equals(candidate.path("receipt").asText())) {
                    order = candidate;
                    break;
                }
            }
            // Recover an order created remotely before a previous timeout or local rollback.
            if (order == null)
                order = client.call("POST", "/orders", Map.of("amount", amount, "currency", "INR", "receipt", b.getBookingReference()), null);
            if (order.path("amount").asLong() != amount || !"INR".equals(order.path("currency").asText()))
                throw new BookingValidationException("Gateway order amount mismatch");
            p = new Payment();
            p.setBookingId(id);
            p.setIdempotencyKey(key);
            p.setPaymentToken("razorpay");
            p.setAmount(b.getFinalAmount());
            p.setStatus("ORDER_CREATED");
            p.setGatewayOrderId(RazorpayClient.id(order.path("id").asText()));
            p.setProviderReference(p.getGatewayOrderId());
            payments.save(p);
        }
        return Map.of("keyId", client.keyId(), "orderId", p.getGatewayOrderId(), "amount", p.getAmount().movePointRight(2).longValueExact(), "currency", p.getCurrency());
    }

    @Transactional
    public Payment verify(Long id, Long user, String orderId, String paymentId, String signature) {
        owned(id, user);
        Payment p = payments.findByGatewayOrderId(orderId).orElseThrow(() -> new BookingValidationException("Unknown order"));
        if (!p.getBookingId().equals(id)) throw new BookingValidationException("Order does not match booking");
        client.verifyCheckout(p.getGatewayOrderId(), paymentId, signature);
        return captured(p.getGatewayOrderId(), paymentId);
    }

    @Transactional
    public Payment captured(String orderId, String paymentId) {
        Payment initial = payments.findByGatewayOrderId(orderId).orElse(null);
        if (initial == null) return null; // unrelated merchant order
        Booking b = bookings.findLockedById(initial.getBookingId()).orElseThrow();
        Payment p = payments.findById(initial.getId()).orElseThrow();
        entityManager.refresh(p);
        if (p.getGatewayPaymentId() != null && !p.getGatewayPaymentId().equals(paymentId))
            throw new BookingValidationException("Unexpected payment for settled order");
        if (List.of("SUCCEEDED", "REFUND_PENDING", "REFUNDED").contains(p.getStatus())) return p;
        JsonNode remote = client.call("GET", "/payments/" + RazorpayClient.id(paymentId), null, null);
        if (!paymentId.equals(remote.path("id").asText()) || !orderId.equals(remote.path("order_id").asText())
                || p.getAmount().movePointRight(2).longValueExact() != remote.path("amount").asLong()
                || !p.getCurrency().equals(remote.path("currency").asText()))
            throw new BookingValidationException("Gateway payment does not match order, amount or currency");
        if (!"captured".equals(remote.path("status").asText())) return p;
        p.setGatewayPaymentId(paymentId);
        p.setStatus("SUCCEEDED");
        p.setProviderReference(paymentId);
        payments.saveAndFlush(p);
        outbox.saveBookingEvent(b, "PAYMENT_SUCCEEDED");
        Show show = shows.findById(b.getShowId()).orElseThrow();
        if (b.getStatus() == BookingStatus.PENDING_PAYMENT && b.getExpiryTime().isAfter(LocalDateTime.now())
                && "ACTIVE".equals(show.getStatus()) && show.getShowTime().isAfter(LocalDateTime.now())) {
            bookingService.confirmBooking(b.getId(), b.getUserId());
        } else {
            // A late capture must never reclaim seats already released or sold again.
            bookingService.queueGatewayRefund(b, p);
        }
        return p;
    }

    @Transactional
    public void processRefund(Long refundId) {
        Refund initial = refunds.findById(refundId).orElseThrow();
        Booking b = bookings.findLockedById(initial.getBookingId()).orElseThrow();
        Refund r = refunds.findById(refundId).orElseThrow();
        entityManager.refresh(r);
        Payment p = payments.findById(r.getPaymentId()).orElseThrow();
        if (p.getGatewayOrderId() == null || !"PENDING".equals(r.getStatus())) return;
        JsonNode remote = r.getProviderReference().startsWith("rfnd_")
                ? client.call("GET", "/refunds/" + RazorpayClient.id(r.getProviderReference()), null, null)
                : client.call("POST", "/payments/" + RazorpayClient.id(p.getGatewayPaymentId()) + "/refund",
                Map.of("amount", r.getAmount().movePointRight(2).longValueExact()), "booking-refund-" + r.getId());
        if (!p.getGatewayPaymentId().equals(remote.path("payment_id").asText())
                || r.getAmount().movePointRight(2).longValueExact() != remote.path("amount").asLong()
                || !r.getCurrency().equals(remote.path("currency").asText()))
            throw new BookingValidationException("Gateway refund mismatch");
        r.setProviderReference(RazorpayClient.id(remote.path("id").asText()));
        if ("processed".equals(remote.path("status").asText())) {
            r.setStatus("SUCCEEDED");
            p.setStatus("REFUNDED");
            outbox.saveBookingEvent(b, "PAYMENT_REFUNDED");
        } else if ("failed".equals(remote.path("status").asText())) r.setStatus("FAILED");
    }
}
