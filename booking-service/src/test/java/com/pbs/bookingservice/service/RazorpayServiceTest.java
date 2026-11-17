package com.pbs.bookingservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbs.bookingservice.common.ex.BookingValidationException;
import com.pbs.bookingservice.entity.Booking;
import com.pbs.bookingservice.entity.Payment;
import com.pbs.bookingservice.entity.Show;
import com.pbs.bookingservice.entity.enums.BookingStatus;
import com.pbs.bookingservice.repository.BookingRepository;
import com.pbs.bookingservice.repository.PaymentRepository;
import com.pbs.bookingservice.repository.RefundRepository;
import com.pbs.bookingservice.repository.ShowRepository;
import com.pbs.bookingservice.saga.OutboxService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class RazorpayServiceTest {
    final RazorpayClient client = mock(RazorpayClient.class);
    final BookingRepository bookings = mock(BookingRepository.class);
    final PaymentRepository payments = mock(PaymentRepository.class);
    final RefundRepository refunds = mock(RefundRepository.class);
    final BookingService bookingService = mock(BookingService.class);
    final ShowRepository shows = mock(ShowRepository.class);
    final OutboxService outbox = mock(OutboxService.class);
    final RazorpayService service = new RazorpayService(mock(EntityManager.class), client, bookings, payments, refunds, bookingService, shows, outbox);
    final Payment payment = new Payment();
    final Booking booking = Booking.builder().id(1L).userId(2L).showId(3L).status(BookingStatus.PENDING_PAYMENT)
            .expiryTime(LocalDateTime.now().plusMinutes(10)).build();

    RazorpayServiceTest() {
        payment.setId(4L);
        payment.setBookingId(1L);
        payment.setAmount(new BigDecimal("625.00"));
        payment.setGatewayOrderId("order_1");
        payment.setStatus("ORDER_CREATED");
        when(payments.findByGatewayOrderId("order_1")).thenReturn(Optional.of(payment));
        when(payments.findById(4L)).thenReturn(Optional.of(payment));
        when(bookings.findLockedById(1L)).thenReturn(Optional.of(booking));
        when(shows.findById(3L)).thenReturn(Optional.of(Show.builder().status("ACTIVE").showTime(LocalDateTime.now().plusDays(1)).build()));
    }

    void remote(String status, int amount, String currency) throws Exception {
        when(client.call("GET", "/payments/pay_1", null, null)).thenReturn(new ObjectMapper().readTree(
                "{\"id\":\"pay_1\",\"order_id\":\"order_1\",\"amount\":" + amount + ",\"currency\":\"" + currency + "\",\"status\":\"" + status + "\"}"));
    }

    @Test
    void amountAndCurrencyMustMatchStoredOrder() throws Exception {
        remote("captured", 62499, "INR");
        assertThrows(BookingValidationException.class, () -> service.captured("order_1", "pay_1"));
        remote("captured", 62500, "USD");
        assertThrows(BookingValidationException.class, () -> service.captured("order_1", "pay_1"));
        verifyNoInteractions(bookingService, outbox);
    }

    @Test
    void authorizationAloneCannotConfirm() throws Exception {
        remote("authorized", 62500, "INR");
        assertEquals("ORDER_CREATED", service.captured("order_1", "pay_1").getStatus());
        verifyNoInteractions(bookingService, outbox);
    }

    @Test
    void capturedPaymentConfirmsExactlyOnce() throws Exception {
        remote("captured", 62500, "INR");
        service.captured("order_1", "pay_1");
        service.captured("order_1", "pay_1");
        verify(bookingService, times(1)).confirmBooking(1L, 2L);
        verify(outbox, times(1)).saveBookingEvent(booking, "PAYMENT_SUCCEEDED");
    }

    @Test
    void expiredReservationQueuesCompensationInsteadOfConfirmation() throws Exception {
        booking.setStatus(BookingStatus.EXPIRED_BOOKING);
        remote("captured", 62500, "INR");
        service.captured("order_1", "pay_1");
        verify(bookingService).queueGatewayRefund(booking, payment);
        verify(bookingService, never()).confirmBooking(anyLong(), anyLong());
    }

    @Test
    void simultaneousCheckoutTabsReuseTheStoredOrderEvenWithDifferentKeys() {
        when(payments.findByBookingIdOrderById(1L)).thenReturn(List.of(payment));
        when(client.keyId()).thenReturn("rzp_test_example");
        var first = service.order(1L, 2L, "first-tab");
        var second = service.order(1L, 2L, "second-tab");
        assertEquals(first, second);
        assertEquals("order_1", second.get("orderId"));
        verify(client, never()).call(anyString(), anyString(), any(), any());
        verify(payments, never()).save(any());
    }

    @Test
    void settledPaymentsCannotOpenAnotherCheckoutEvenIfBookingStateIsInconsistent() {
        when(payments.findByBookingIdOrderById(1L)).thenReturn(List.of(payment));
        for (String status : List.of("SUCCEEDED", "REFUND_PENDING", "REFUNDED")) {
            payment.setStatus(status);
            assertThrows(BookingValidationException.class, () -> service.order(1L, 2L, "retry"));
        }
        verifyNoInteractions(client);
    }

    @Test
    void idempotencyKeysRejectWhitespaceControlCharactersAndExcessiveLength() {
        for (String key : List.of("", " ", "key\n", "two words", "x".repeat(101)))
            assertThrows(BookingValidationException.class, () -> service.order(1L, 2L, key));
        verifyNoInteractions(client);
    }

    @Test
    void mismatchedSecondCaptureCannotReplaceTheSettledPayment() throws Exception {
        remote("captured", 62500, "INR");
        service.captured("order_1", "pay_1");
        assertThrows(BookingValidationException.class, () -> service.captured("order_1", "pay_2"));
        assertEquals("pay_1", payment.getGatewayPaymentId());
        verify(bookingService, times(1)).confirmBooking(1L, 2L);
    }

    @Test
    void recoveredPaidOrderIsPersistedForReconciliationAfterLocalRollback() throws Exception {
        booking.setBookingReference("booking-recovery");
        booking.setFinalAmount(new BigDecimal("625.00"));
        when(payments.findByBookingIdOrderById(1L)).thenReturn(List.of());
        when(client.keyId()).thenReturn("rzp_test_example");
        when(client.call(eq("GET"), startsWith("/orders?receipt="), isNull(), isNull()))
                .thenReturn(new ObjectMapper().readTree("{\"items\":[{\"id\":\"order_recovered\",\"receipt\":\"booking-recovery\",\"amount\":62500,\"currency\":\"INR\",\"status\":\"paid\"}]}"));
        assertEquals("order_recovered", service.order(1L, 2L, "recovery-key").get("orderId"));
        verify(payments).save(argThat(p -> "order_recovered".equals(p.getGatewayOrderId())
                && "ORDER_CREATED".equals(p.getStatus()) && p.getBookingId().equals(1L)));
        verify(client, never()).call(eq("POST"), anyString(), any(), any());
    }
}
