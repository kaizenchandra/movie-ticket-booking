package com.pbs.bookingservice.common.ex;

public class BookingConflictException extends BookingValidationException {
    public BookingConflictException(String message) {
        super(message);
    }
}
