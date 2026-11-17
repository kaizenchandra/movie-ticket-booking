package com.pbs.bookingservice.repository;

import com.pbs.bookingservice.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {
    Optional<Booking> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:requestKey, 0))", nativeQuery = true)
    Integer lockBookingRequest(String requestKey);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.id = :id")
    java.util.Optional<Booking> findLockedById(Long id);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.status = com.pbs.bookingservice.entity.enums.BookingStatus.PENDING_PAYMENT AND b.expiryTime <= :now")
    List<Booking> findExpiredBookings(LocalDateTime now);


    @Query("SELECT b FROM Booking b WHERE b.id = :bookingId AND b.userId = :userId")
    Optional<Booking> findByBookingIdAndUserId(Long bookingId, Long userId);

    @Query("SELECT b FROM Booking b WHERE b.userId = :userId")
    List<Booking> findByUserId(long userId);
}
