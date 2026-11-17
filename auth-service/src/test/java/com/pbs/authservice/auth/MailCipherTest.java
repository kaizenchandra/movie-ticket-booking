package com.pbs.authservice.auth;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MailCipherTest {
    @Test
    void pendingMailUsesAuthenticatedEncryptionAndUniqueNonces() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT secret FROM auth.mail_key WHERE id=1", String.class))
                .thenReturn(Base64.getEncoder().encodeToString(new byte[32]));
        var cipher = new MailCipher(jdbc);
        String body = "https://localhost/reset#token=secret-token";
        String first = cipher.encrypt(body), second = cipher.encrypt(body);
        assertNotEquals(first, second);
        assertFalse(first.contains("secret-token"));
        assertEquals(body, cipher.decrypt(first));
        String[] parts = first.split(":");
        byte[] damaged = Base64.getDecoder().decode(parts[1]);
        damaged[0] ^= 1;
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(parts[0] + ":" + Base64.getEncoder().encodeToString(damaged)));
    }
}
