package com.pbs.bookingservice.service;

import com.github.f4b6a3.ulid.UlidCreator;
import com.pbs.bookingservice.common.ex.*;
import com.pbs.bookingservice.common.req.BookingRequest;
import com.pbs.bookingservice.common.resp.BookingResponse;
import com.pbs.bookingservice.common.resp.PricingDetails;
import com.pbs.bookingservice.entity.Booking;
import com.pbs.bookingservice.entity.BookingSeat;
import com.pbs.bookingservice.entity.SeatInventory;
import com.pbs.bookingservice.entity.Show;
import com.pbs.bookingservice.entity.enums.BookingSeatStatus;
import com.pbs.bookingservice.entity.enums.BookingStatus;
import com.pbs.bookingservice.entity.enums.SeatStatus;
import com.pbs.bookingservice.repository.BookingRepository;
import com.pbs.bookingservice.repository.SeatInventoryRepository;
import com.pbs.bookingservice.repository.ShowRepository;
import com.pbs.bookingservice.saga.OutboxService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {
    private static final int BOOKING_EXPIRY_MINUTES = 15;
    private static final long BOOKING_EXPIRY_CHECK_DELAY_MS = 60000;

    private final jakarta.persistence.EntityManager entityManager;
    private final PricingService pricingService;
    private final SeatInventoryRepository seatInventoryRepository;
    private final BookingRepository bookingRepository;
    private final OutboxService outboxService;
    private final ShowRepository showRepository;
    private final com.pbs.bookingservice.repository.PaymentRepository payments;
    private final com.pbs.bookingservice.repository.RefundRepository refunds;
    private final LocalPaymentProvider paymentProvider;

    @Transactional
    public BookingResponse initiateBooking(BookingRequest request) {
        return initiateBooking(request, null);
    }

    @Transactional
    public BookingResponse initiateBooking(BookingRequest request, String key) {
        String fingerprint = null;
        if (key != null) {
            if (key.isBlank() || key.length() > 100) throw new BookingValidationException("Invalid Idempotency-Key");
            if (request.getUserId() == null || request.getSeatNumbers() == null || request.getSeatNumbers().stream().anyMatch(java.util.Objects::isNull))
                throw new BookingValidationException("User and seats are required");
            fingerprint = bookingFingerprint(request);
            // Transaction-scoped PostgreSQL lock also serializes the first request, before a booking row exists.
            bookingRepository.lockBookingRequest(request.getUserId() + ":" + key);
            var existing = bookingRepository.findByUserIdAndIdempotencyKey(request.getUserId(), key);
            if (existing.isPresent()) {
                if (!fingerprint.equals(existing.get().getRequestFingerprint()))
                    throw new BookingConflictException("Idempotency-Key was already used for a different booking request");
                return new BookingResponse(existing.get());
            }
        }
        log.debug("Initiating booking for userId: {}, showId: {}, seats: {}",
                request.getUserId(), request.getShowId(), request.getSeatNumbers());

        validateBookingRequest(request);

        // Acquire seat locks
        List<SeatInventory> seats = validateAndLockSeats(
                request.getShowId(),
                request.getSeatNumbers(),
                request.getUserId()
        );
        log.info("Successfully locked {} seats for userId: {}, showId: {}",
                seats.size(), request.getUserId(), request.getShowId());

        // Pricing
        PricingDetails pricing =
                pricingService.calculatePricing(seats, request.getOfferCode());
        log.info("Pricing calculated - Base: {}, Discount: {}, Final: {} for userId: {}",
                pricing.getBaseAmount(), pricing.getDiscountAmount(), pricing.getFinalAmount(), request.getUserId());

        // Create booking
        Booking booking = createBookingRecord(request, seats, pricing);
        booking.setIdempotencyKey(key);
        booking.setRequestFingerprint(fingerprint);
        log.info("Booking created with reference: {} for userId: {}",
                booking.getBookingReference(), request.getUserId());

        // Write Outbox Event (same transaction)
        outboxService.saveBookingInitiatedEvent(booking);


        // Publish Kafka event

        log.info("Booking initiated successfully with reference: {}", booking.getBookingReference());
        return new BookingResponse(booking);
    }

    private String bookingFingerprint(BookingRequest request) {
        try {
            var values = new java.util.ArrayList<String>();
            values.add(String.valueOf(request.getShowId()));
            values.add(String.valueOf(request.getSeatCount()));
            values.add(String.valueOf(request.getOfferCode()));
            values.addAll(request.getSeatNumbers().stream().sorted().toList());
            StringBuilder canonical = new StringBuilder();
            values.forEach(value -> canonical.append(value.length()).append(':').append(value));
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Booking createBookingRecord(
            BookingRequest request,
            List<SeatInventory> seats,
            PricingDetails pricing) {
        log.debug("Creating booking record for userId: {}, showId: {}, seats count: {}",
                request.getUserId(), request.getShowId(), seats.size());

        Booking booking = new Booking();
        booking.setBookingReference(UlidCreator.getUlid().toString());
        booking.setUserId(request.getUserId());
        booking.setShowId(request.getShowId());
        booking.setStatus(BookingStatus.PENDING_PAYMENT);
        booking.setBaseAmount(pricing.getBaseAmount());
        booking.setDiscountAmount(pricing.getDiscountAmount());
        booking.setFinalAmount(pricing.getFinalAmount());
        booking.setBookingTime(LocalDateTime.now());
        booking.setExpiryTime(LocalDateTime.now().plusMinutes(BOOKING_EXPIRY_MINUTES));

        List<BookingSeat> bookingSeats = seats.stream()
                .map(seat -> createBookingSeat(booking, seat))
                .toList();

        booking.setBookingSeats(bookingSeats);
        return bookingRepository.save(booking);
    }


    private List<SeatInventory> validateAndLockSeats(
            Long showId,
            List<String> seatNumbers,
            Long userId) {
        log.debug("Validating and locking seats for showId: {}, userId: {}, seatNumbers: {}",
                showId, userId, seatNumbers);

        List<SeatInventory> seats =
                seatInventoryRepository.findByShowIdAndSeatNumberIn(showId, seatNumbers);

        if (seats.size() != seatNumbers.size()) {
            log.error("Seats not found - Requested: {}, Found: {} for showId: {}",
                    seatNumbers.size(), seats.size(), showId);
            throw new BookingException("Some seats not found");
        }

        seats.forEach(entityManager::refresh);
        seats.forEach(this::validateSeatAvailability);
        updateSeatsStatus(seats, SeatStatus.RESERVED);
        log.info("Successfully validated and locked {} seats for userId: {}, showId: {}",
                seats.size(), userId, showId);
        return seats;
    }

    @Transactional
    public void confirmBooking(Long bookingId, Long userId) {
        log.debug("Confirming booking with bookingId: {}", bookingId);

        Booking booking = bookingRepository.findLockedById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException("Booking not found"));

        validateBookingOwnership(booking, userId);
        if (booking.getStatus() == BookingStatus.CONFIRMED_BOOKING) return;
        validateBookingForConfirmation(booking);
        if (payments.findByBookingIdAndStatus(bookingId, "SUCCEEDED").isEmpty())
            throw new BookingValidationException("A successful payment is required before confirmation");

        outboxService.saveBookingConfirmedEvent(booking);

        Show show = showRepository.findLockedById(booking.getShowId())
                .orElseThrow(() -> new BookingException("Show not found"));
        entityManager.refresh(show); // Lock queries may return an entity cached before waiting for the lock.
        List<SeatInventory> seats = getSeatsForBooking(booking);
        if (seats.size() != booking.getBookingSeats().size()
                || seats.stream().anyMatch(seat -> seat.getSeatStatus() != SeatStatus.RESERVED))
            throw new BookingValidationException("Booking no longer owns reserved seats");
        updateSeatsStatus(seats, SeatStatus.BOOKED);


        booking.setStatus(BookingStatus.CONFIRMED_BOOKING);
        booking.getBookingSeats()
                .forEach(bookingSeat -> bookingSeat.setStatus(BookingSeatStatus.BOOKED));
        bookingRepository.save(booking);

        show.setAvailableSeats(show.getAvailableSeats() - seats.size());
        showRepository.save(show);


        log.info("Booking confirmed successfully - bookingId: {}, reference: {}, userId: {}",
                bookingId, booking.getBookingReference(), booking.getUserId());

    }

    private Booking ownedBooking(Long id, Long userId) {
        Booking booking = bookingRepository.findLockedById(id)
                .orElseThrow(() -> new BookingNotFoundException("Booking not found"));
        validateBookingOwnership(booking, userId);
        return booking;
    }

    @Transactional
    public com.pbs.bookingservice.entity.Payment pay(Long id, Long userId, String key, String token) {
        Booking booking = ownedBooking(id, userId);
        if (key == null || key.isBlank() || key.length() > 100)
            throw new BookingValidationException("Idempotency-Key must contain 1 to 100 characters");
        var previous = payments.findByBookingIdAndIdempotencyKey(id, key);
        if (previous.isPresent()) {
            if (!java.util.Objects.equals(previous.get().getPaymentToken(), token))
                throw new BookingValidationException("Idempotency-Key was already used with a different payment token");
            return previous.get();
        }
        validateBookingForConfirmation(booking);
        Show payableShow = showRepository.findById(booking.getShowId()).orElseThrow();
        if (!"ACTIVE".equals(payableShow.getStatus()) || !payableShow.getShowTime().isAfter(LocalDateTime.now()))
            throw new BookingValidationException("Payment is unavailable for a show that has started or is inactive");
        boolean accepted = paymentProvider.charge(token);
        var payment = new com.pbs.bookingservice.entity.Payment();
        payment.setBookingId(id);
        payment.setIdempotencyKey(key);
        payment.setPaymentToken(token);
        payment.setAmount(booking.getFinalAmount());
        payment.setStatus(accepted ? "SUCCEEDED" : "DECLINED");
        payment.setProviderReference("local-payment-" + UlidCreator.getUlid());
        payments.saveAndFlush(payment);
        outboxService.saveBookingEvent(booking, accepted ? "PAYMENT_SUCCEEDED" : "PAYMENT_DECLINED");
        if (accepted) confirmBooking(id, userId);
        return payment;
    }

    @Transactional
    public java.util.List<com.pbs.bookingservice.entity.Payment> getPayments(Long id, Long userId) {
        ownedBooking(id, userId);
        return payments.findByBookingIdOrderById(id);
    }

    @Transactional
    public BookingResponse cancel(Long id, Long userId) {
        Booking booking = ownedBooking(id, userId);
        if (booking.getStatus() == BookingStatus.CANCELLED_BOOKING) return new BookingResponse(booking);
        boolean confirmed = booking.getStatus() == BookingStatus.CONFIRMED_BOOKING;
        if (!confirmed && booking.getStatus() != BookingStatus.PENDING_PAYMENT)
            throw new BookingValidationException("Only pending or confirmed bookings can be cancelled");
        Show show = showRepository.findLockedById(booking.getShowId()).orElseThrow();
        entityManager.refresh(show);
        if (!show.getShowTime().isAfter(LocalDateTime.now()))
            throw new BookingValidationException("Cancellation is unavailable after showtime");
        updateSeatsStatus(getSeatsForBooking(booking), SeatStatus.AVAILABLE);
        if (confirmed) show.setAvailableSeats(show.getAvailableSeats() + booking.getBookingSeats().size());
        booking.setStatus(BookingStatus.CANCELLED_BOOKING);
        booking.getBookingSeats().forEach(seat -> seat.setStatus(BookingSeatStatus.CANCELLED));
        outboxService.saveBookingEvent(booking, "BOOKING_CANCELLED");
        if (payments.findByBookingIdAndStatus(id, "SUCCEEDED").isPresent()) refund(id, userId);
        return new BookingResponse(booking);
    }

    @Transactional
    public com.pbs.bookingservice.entity.Refund refund(Long id, Long userId) {
        Booking booking = ownedBooking(id, userId);
        if (booking.getStatus() != BookingStatus.CANCELLED_BOOKING)
            throw new BookingValidationException("Cancel the booking before requesting a refund");
        var existing = refunds.findByBookingId(id);
        if (existing.isPresent()) return existing.get();
        var payment = payments.findByBookingIdAndStatus(id, "SUCCEEDED")
                .orElseThrow(() -> new BookingValidationException("No successful payment to refund"));
        if (payment.getGatewayOrderId() != null) return queueGatewayRefund(booking, payment);
        var refund = new com.pbs.bookingservice.entity.Refund();
        refund.setBookingId(id);
        refund.setPaymentId(payment.getId());
        refund.setAmount(payment.getAmount());
        refund.setProviderReference("local-refund-" + payment.getId());
        refunds.save(refund);
        payment.setStatus("REFUNDED");
        outboxService.saveBookingEvent(booking, "PAYMENT_REFUNDED");
        return refund;
    }

    public com.pbs.bookingservice.entity.Refund queueGatewayRefund(Booking booking, com.pbs.bookingservice.entity.Payment payment) {
        var existing = refunds.findByBookingId(booking.getId());
        if (existing.isPresent()) return existing.get();
        var refund = new com.pbs.bookingservice.entity.Refund();
        refund.setBookingId(booking.getId());
        refund.setPaymentId(payment.getId());
        refund.setAmount(payment.getAmount());
        refund.setStatus("PENDING");
        refund.setProviderReference("pending");
        payment.setStatus("REFUND_PENDING");
        return refunds.save(refund);
    }

    @Transactional
    public com.pbs.bookingservice.entity.Refund getRefund(Long id, Long userId) {
        ownedBooking(id, userId);
        return refunds.findByBookingId(id).orElseThrow(() -> new BookingNotFoundException("Refund not found"));
    }

    @Scheduled(fixedDelay = BOOKING_EXPIRY_CHECK_DELAY_MS)
    @Transactional
    public void expireBookings() {
        log.debug("Running scheduled task to expire bookings");

        List<Booking> expiredBookings =
                bookingRepository.findExpiredBookings(LocalDateTime.now());

        if (expiredBookings == null || expiredBookings.isEmpty()) {
            return;
        }

        log.info("Found {} expired bookings to process", expiredBookings.size());
        for (Booking booking : expiredBookings) {
            expireBooking(booking);
        }
    }

    @Transactional
    public BookingResponse getBookingById(Long bookingId, Long userId) {
        log.debug("Retrieving booking - bookingId: {}, userId: {}", bookingId, userId);
        Booking booking = bookingRepository.findByBookingIdAndUserId(bookingId, userId)
                .orElseThrow(() -> new BookingNotFoundException("Booking not found"));

        validateBookingOwnership(booking, userId);
        return new BookingResponse(booking);
    }

    private void validateBookingRequest(BookingRequest request) {
        if (request.getSeatNumbers() == null || request.getSeatNumbers().isEmpty()
                || request.getSeatNumbers().size() > 10
                || request.getSeatNumbers().stream().anyMatch(x -> x == null || x.isBlank())
                || new java.util.HashSet<>(request.getSeatNumbers()).size() != request.getSeatNumbers().size()
                || request.getSeatCount() == null || request.getSeatCount() != request.getSeatNumbers().size()) {
            throw new BookingValidationException("Provide 1 to 10 distinct seats matching seatCount");
        }
        if (request.getShowId() == null) throw new BookingValidationException("Show ID is required");
        Show show = showRepository.findById(request.getShowId())
                .orElseThrow(() -> new BookingValidationException("Show not found"));
        if (!"ACTIVE".equals(show.getStatus()) || !show.getShowTime().isAfter(LocalDateTime.now())) {
            throw new BookingValidationException("Show is no longer available for booking");
        }
        if (request.getShowId() == null) {
            log.error("Booking validation failed: Show ID is missing");
            throw new BookingException("Show ID is required");
        }
        if (request.getUserId() == null) {
            log.error("Booking validation failed: User ID is missing");
            throw new BookingException("User ID is required");
        }
        if (request.getSeatNumbers() == null || request.getSeatNumbers().isEmpty()) {
            log.error("Booking validation failed: Seat numbers are missing or empty");
            throw new BookingException("Seat numbers are required");
        }
    }

    private void validateSeatAvailability(SeatInventory seat) {
        if (!SeatStatus.AVAILABLE.equals(seat.getSeatStatus())) {
            log.error("Seat validation failed - Seat {} is not available, current status: {}",
                    seat.getSeatNumber(), seat.getSeatStatus());
            throw new SeatUnavailableException("Seat is not available. " + seat.getSeatNumber());
        }
    }

    private void validateBookingForConfirmation(Booking booking) {
        if (!BookingStatus.PENDING_PAYMENT.equals(booking.getStatus())) {
            log.error("Booking confirmation validation failed - Invalid state: {} for booking ref: {}",
                    booking.getStatus(), booking.getBookingReference());
            throw new BookingValidationException("Invalid booking state for booking ref" + booking.getBookingReference());
        }
        // temp commented to testing booking
        if (booking.getExpiryTime().isBefore(LocalDateTime.now())) {
            throw new BookingValidationException("Booking has expired for booking ref " + booking.getBookingReference());
        }
    }

    private void validateBookingOwnership(Booking booking, Long userId) {
        if (!booking.getUserId().equals(userId)) {
            log.error("Booking ownership validation failed - bookingId: {}, expected userId: {}, actual userId: {}",
                    booking.getId(), userId, booking.getUserId());
            throw new BookingValidationException("Booking does not belong to this user");
        }
    }

    private BookingSeat createBookingSeat(Booking booking, SeatInventory seat) {
        BookingSeat bookingSeat = new BookingSeat();
        bookingSeat.setBooking(booking);
        bookingSeat.setSeatInventoryId(seat.getId());
        bookingSeat.setPricePaid(seat.getPrice());
        bookingSeat.setStatus(BookingSeatStatus.PENDING);
        bookingSeat.setSeatNumber(seat.getSeatNumber());
        return bookingSeat;
    }

    private List<SeatInventory> getSeatsForBooking(Booking booking) {
        List<Long> seatInventoryIds = booking.getBookingSeats()
                .stream()
                .map(BookingSeat::getSeatInventoryId)
                .toList();
        var seats = seatInventoryRepository.findLockedByIds(seatInventoryIds);
        seats.forEach(entityManager::refresh);
        return seats;
    }

    private void updateSeatsStatus(List<SeatInventory> seats, SeatStatus status) {
        seats.forEach(seat -> seat.setSeatStatus(status));
        seatInventoryRepository.saveAll(seats);
    }

    private void expireBooking(Booking booking) {
        log.info("Expiring booking - bookingId: {}, reference: {}, userId: {}",
                booking.getId(), booking.getBookingReference(), booking.getUserId());
        booking.setStatus(BookingStatus.EXPIRED_BOOKING);
        booking.getBookingSeats().forEach(bookingSeat -> bookingSeat.setStatus(BookingSeatStatus.EXPIRED));
        bookingRepository.save(booking);

        outboxService.saveBookingExpiredEvent(booking);

        List<SeatInventory> seats = getSeatsForBooking(booking);

        seats.forEach(seat -> {
            seat.setSeatStatus(SeatStatus.AVAILABLE);

        });

        seatInventoryRepository.saveAll(seats);

    }

    @Transactional
    public List<BookingResponse> getAllBookingsByUserId(long userId) {
        log.debug("Retrieving all bookings for userId: {}", userId);

        List<Booking> bookings = bookingRepository.findByUserId(userId);
        log.info("Found {} bookings for userId: {}", bookings.size(), userId);

        return bookings.stream()
                .map(BookingResponse::new)
                .toList();
    }
}
