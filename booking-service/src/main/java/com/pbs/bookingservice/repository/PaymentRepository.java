package com.pbs.bookingservice.repository;

import com.pbs.bookingservice.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    java.util.Optional<Payment> findByGatewayPaymentId(String paymentId);

    java.util.Optional<Payment> findByGatewayOrderId(String orderId);

    java.util.List<Payment> findTop50ByGatewayOrderIdIsNotNullAndStatusAndIdGreaterThanOrderById(String status, Long afterId);

    java.util.Optional<Payment> findByBookingIdAndIdempotencyKey(Long bookingId, String key);

    java.util.Optional<Payment> findByBookingIdAndStatus(Long bookingId, String status);

    java.util.List<Payment> findByBookingIdOrderById(Long bookingId);
}
