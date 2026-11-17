package com.pbs.bookingservice.repository;

import com.pbs.bookingservice.entity.Refund;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, Long> {
    java.util.List<Refund> findTop50ByStatusOrderById(String status);

    java.util.Optional<Refund> findByBookingId(Long bookingId);
}
