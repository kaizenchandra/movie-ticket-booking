package com.pbs.bookingservice.service;

import com.pbs.bookingservice.common.ex.BookingValidationException;
import com.pbs.bookingservice.common.req.BookingRequest;
import com.pbs.bookingservice.common.req.OfferDiscountCode;
import com.pbs.bookingservice.entity.*;
import com.pbs.bookingservice.entity.enums.BookingStatus;
import com.pbs.bookingservice.entity.enums.SeatStatus;
import com.pbs.bookingservice.repository.*;
import com.pbs.bookingservice.saga.OutboxService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingWorkflowTest {
    final BookingRepository bookings = mock(BookingRepository.class);
    final SeatInventoryRepository inventory = mock(SeatInventoryRepository.class);
    final ShowRepository shows = mock(ShowRepository.class);
    final OutboxService outbox = mock(OutboxService.class);
    final PaymentRepository payments = mock(PaymentRepository.class);
    final RefundRepository refunds = mock(RefundRepository.class);
    final LocalPaymentProvider provider = mock(LocalPaymentProvider.class);
    final jakarta.persistence.EntityManager entityManager = mock(jakarta.persistence.EntityManager.class);
    final BookingService service = new BookingService(entityManager, new PricingService(new OfferService()), inventory, bookings, outbox, shows, payments, refunds, provider);

    @Test
    void discountAppliedOnceAndOptional() {
        var seats = List.of(SeatInventory.builder().price(250d).build(), SeatInventory.builder().price(250d).build(), SeatInventory.builder().price(250d).build());
        var pricing = new PricingService(new OfferService());
        assertEquals(0, new BigDecimal("625").compareTo(pricing.calculatePricing(seats, OfferDiscountCode.THIRD_TICKET_50_DISCOUNT).getFinalAmount()));
        assertEquals(0, new BigDecimal("750").compareTo(pricing.calculatePricing(seats, null).getFinalAmount()));
    }

    Booking pending() {
        return Booking.builder().id(1L).userId(2L).showId(3L).bookingReference("test")
                .status(BookingStatus.PENDING_PAYMENT).expiryTime(LocalDateTime.now().plusMinutes(15))
                .bookingSeats(new ArrayList<>()).build();
    }

    @Test
    void readingDoesNotConfirmBooking() {
        var booking = pending();
        when(bookings.findByBookingIdAndUserId(1L, 2L)).thenReturn(Optional.of(booking));
        service.getBookingById(1L, 2L);
        assertEquals(BookingStatus.PENDING_PAYMENT, booking.getStatus());
        verify(bookings, never()).save(any());
    }

    @Test
    void anotherUserCannotConfirm() {
        when(bookings.findLockedById(1L)).thenReturn(Optional.of(pending()));
        assertThrows(BookingValidationException.class, () -> service.confirmBooking(1L, 99L));
        verifyNoInteractions(outbox, inventory);
    }

    @Test
    void expiredBookingCannotConfirm() {
        var booking = pending();
        booking.setExpiryTime(LocalDateTime.now().minusSeconds(1));
        when(bookings.findLockedById(1L)).thenReturn(Optional.of(booking));
        assertThrows(BookingValidationException.class, () -> service.confirmBooking(1L, 2L));
        verifyNoInteractions(outbox, inventory);
    }

    @Test
    void confirmationIsIdempotent() {
        var booking = pending();
        booking.setStatus(BookingStatus.CONFIRMED_BOOKING);
        when(bookings.findLockedById(1L)).thenReturn(Optional.of(booking));
        service.confirmBooking(1L, 2L);
        verifyNoInteractions(outbox, inventory, shows);
    }

    @Test
    void duplicateSeatsRejectedBeforeReservation() {
        var request = BookingRequest.builder().userId(2L).showId(3L).seatCount(2).seatNumbers(List.of("A1", "A1")).build();
        assertThrows(BookingValidationException.class, () -> service.initiateBooking(request));
        verifyNoInteractions(inventory, bookings, outbox);
    }

    @Test
    void unpaidConfirmationRejected() {
        when(bookings.findLockedById(1L)).thenReturn(Optional.of(pending()));
        assertThrows(BookingValidationException.class, () -> service.confirmBooking(1L, 2L));
        verifyNoInteractions(outbox, inventory);
    }

    @Test
    void paymentRetryReturnsExistingAttempt() {
        when(bookings.findLockedById(1L)).thenReturn(Optional.of(pending()));
        Payment attempt = new Payment();
        attempt.setPaymentToken("tok_decline");
        when(payments.findByBookingIdAndIdempotencyKey(1L, "key")).thenReturn(Optional.of(attempt));
        assertSame(attempt, service.pay(1L, 2L, "key", "tok_decline"));
        assertThrows(BookingValidationException.class, () -> service.pay(1L, 2L, "key", "tok_success"));
        verifyNoInteractions(provider, outbox);
    }

    @Test
    void cannotRefundActiveBooking() {
        when(bookings.findLockedById(1L)).thenReturn(Optional.of(pending()));
        assertThrows(BookingValidationException.class, () -> service.refund(1L, 2L));
        verifyNoInteractions(refunds, payments);
    }

    @Test
    void refundRetryReturnsExistingRefund() {
        Booking booking = pending();
        booking.setStatus(BookingStatus.CANCELLED_BOOKING);
        when(bookings.findLockedById(1L)).thenReturn(Optional.of(booking));
        Refund refund = new Refund();
        when(refunds.findByBookingId(1L)).thenReturn(Optional.of(refund));
        assertSame(refund, service.refund(1L, 2L));
        verifyNoInteractions(payments, outbox);
    }

    @Test
    void cannotCancelAfterShowtime() {
        when(bookings.findLockedById(1L)).thenReturn(Optional.of(pending()));
        when(shows.findLockedById(3L)).thenReturn(Optional.of(Show.builder().showTime(LocalDateTime.now().minusMinutes(1)).build()));
        assertThrows(BookingValidationException.class, () -> service.cancel(1L, 2L));
        verifyNoInteractions(inventory, refunds, payments);
    }

    @Test
    void bookingRetriesReuseSameReservationAndRejectChangedRequest() {
        var request = BookingRequest.builder().userId(2L).showId(3L).seatCount(2).seatNumbers(List.of("A1", "A2")).build();
        when(shows.findById(3L)).thenReturn(Optional.of(Show.builder().status("ACTIVE").showTime(LocalDateTime.now().plusDays(1)).build()));
        when(inventory.findByShowIdAndSeatNumberIn(3L, request.getSeatNumbers())).thenReturn(List.of(
                SeatInventory.builder().id(10L).seatNumber("A1").price(250d).seatStatus(SeatStatus.AVAILABLE).build(),
                SeatInventory.builder().id(11L).seatNumber("A2").price(250d).seatStatus(SeatStatus.AVAILABLE).build()));
        when(bookings.save(any())).thenAnswer(call -> call.getArgument(0));
        service.initiateBooking(request, "booking-request");
        var saved = org.mockito.ArgumentCaptor.forClass(Booking.class);
        verify(bookings).save(saved.capture());
        when(bookings.findByUserIdAndIdempotencyKey(2L, "booking-request")).thenReturn(Optional.of(saved.getValue()));
        request.setSeatNumbers(List.of("A2", "A1"));
        service.initiateBooking(request, "booking-request");
        verify(inventory, times(1)).findByShowIdAndSeatNumberIn(any(), any());
        verify(outbox, times(1)).saveBookingInitiatedEvent(any());
        request.setSeatNumbers(List.of("A2", "A3"));
        assertThrows(BookingValidationException.class, () -> service.initiateBooking(request, "booking-request"));
    }

    @Test
    void confirmationDoesNotOverwriteAnUnreservedSeat() {
        var booking = pending();
        booking.setBookingSeats(List.of(BookingSeat.builder().seatInventoryId(10L).build()));
        when(bookings.findLockedById(1L)).thenReturn(Optional.of(booking));
        when(payments.findByBookingIdAndStatus(1L, "SUCCEEDED")).thenReturn(Optional.of(new Payment()));
        when(shows.findLockedById(3L)).thenReturn(Optional.of(Show.builder().availableSeats(10).build()));
        when(inventory.findLockedByIds(List.of(10L))).thenReturn(List.of(SeatInventory.builder().seatStatus(SeatStatus.BOOKED).build()));
        assertThrows(BookingValidationException.class, () -> service.confirmBooking(1L, 2L));
        verify(inventory, never()).saveAll(any());
        assertEquals(BookingStatus.PENDING_PAYMENT, booking.getStatus());
    }

    @Test
    void confirmationRefreshesShowAfterLockBeforeUpdatingInventoryCount() {
        var booking = pending();
        booking.setBookingSeats(List.of(BookingSeat.builder().seatInventoryId(10L).build()));
        var show = Show.builder().availableSeats(10).build();
        var seat = SeatInventory.builder().seatStatus(SeatStatus.RESERVED).build();
        when(bookings.findLockedById(1L)).thenReturn(Optional.of(booking));
        when(payments.findByBookingIdAndStatus(1L, "SUCCEEDED")).thenReturn(Optional.of(new Payment()));
        when(shows.findLockedById(3L)).thenReturn(Optional.of(show));
        when(inventory.findLockedByIds(List.of(10L))).thenReturn(List.of(seat));
        doAnswer(call -> {
            show.setAvailableSeats(8);
            return null;
        }).when(entityManager).refresh(show);
        service.confirmBooking(1L, 2L);
        assertEquals(7, show.getAvailableSeats());
        var ordered = inOrder(shows, entityManager);
        ordered.verify(shows).findLockedById(3L);
        ordered.verify(entityManager).refresh(show);
        ordered.verify(entityManager).refresh(seat);
        ordered.verify(shows).save(show);
    }

}
