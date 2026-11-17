package com.pbs.authservice.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * RFC 6238: HMAC-SHA1, six digits, thirty-second steps.
 */
public final class Totp {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {
    }

    public static String base32(byte[] input) {
        StringBuilder out = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte value : input) {
            buffer = (buffer << 8) | (value & 255);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                out.append(ALPHABET.charAt((buffer >> bits) & 31));
            }
        }
        if (bits > 0) out.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return out.toString();
    }

    private static byte[] decode(String input) {
        var out = new java.io.ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (char c : input.toCharArray()) {
            int value = ALPHABET.indexOf(c);
            if (value < 0) throw new IllegalArgumentException("Invalid TOTP secret");
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 255);
            }
        }
        return out.toByteArray();
    }

    public static String code(String secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decode(secret), "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int o = h[h.length - 1] & 15;
            int number = ((h[o] & 127) << 24) | ((h[o + 1] & 255) << 16) | ((h[o + 2] & 255) << 8) | (h[o + 3] & 255);
            return String.format(Locale.ROOT, "%06d", number % 1000000);
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static OptionalLong matchingStep(String secret, String code, Instant now, long lastStep) {
        if (code == null || !code.matches("[0-9]{6}")) return OptionalLong.empty();
        long current = now.getEpochSecond() / 30;
        for (long step = current - 1; step <= current + 1; step++)
            if (step > lastStep && MessageDigest.isEqual(code(secret, step).getBytes(StandardCharsets.US_ASCII), code.getBytes(StandardCharsets.US_ASCII)))
                return OptionalLong.of(step);
        return OptionalLong.empty();
    }
}
