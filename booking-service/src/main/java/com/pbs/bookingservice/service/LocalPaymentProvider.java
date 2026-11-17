package com.pbs.bookingservice.service;

import com.pbs.bookingservice.common.ex.BookingValidationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Local-only payment simulator. No external money movement or card data.
 */
@Component
public class LocalPaymentProvider {
    @Value("${payments.local-enabled:true}")
    private boolean enabled;
    @Value("${razorpay.enabled:false}")
    private boolean razorpayEnabled;

    public boolean charge(String token) {
        if (!enabled || razorpayEnabled) throw new BookingValidationException("Local payments are disabled");
        if (!"tok_success".equals(token) && !"tok_decline".equals(token))
            throw new BookingValidationException("Use a local test token: tok_success or tok_decline");
        return "tok_success".equals(token);
    }
}
