package com.pbs.authservice.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TotpTest {
    final String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void matchesRfc6238Sha1Vectors() {
        assertEquals(secret, Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII)));
        long[] times = {59, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};
        String[] codes = {"287082", "081804", "050471", "005924", "279037", "353130"};
        for (int i = 0; i < times.length; i++) assertEquals(codes[i], Totp.code(secret, times[i] / 30));
    }

    @Test
    void acceptsOnlySkewWindowAndRejectsReplay() {
        Instant now = Instant.ofEpochSecond(1234567890L);
        long step = now.getEpochSecond() / 30;
        assertTrue(Totp.matchingStep(secret, Totp.code(secret, step - 1), now, -1).isPresent());
        assertTrue(Totp.matchingStep(secret, Totp.code(secret, step + 1), now, -1).isPresent());
        assertTrue(Totp.matchingStep(secret, Totp.code(secret, step + 2), now, -1).isEmpty());
        assertTrue(Totp.matchingStep(secret, Totp.code(secret, step), now, step).isEmpty());
        assertTrue(Totp.matchingStep(secret, "12345", now, -1).isEmpty());
        assertTrue(Totp.matchingStep(secret, "abcdef", now, -1).isEmpty());
    }
}
