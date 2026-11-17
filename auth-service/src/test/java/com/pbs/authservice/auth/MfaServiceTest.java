package com.pbs.authservice.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class MfaServiceTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final MailCipher cipher = mock(MailCipher.class);
    final UserDetailsService users = mock(UserDetailsService.class);
    final MfaService service = new MfaService(jdbc, cipher, mock(AuthenticationManager.class), users, mock(BrowserSessions.class), "Movie Booking");
    final Map<String, Object> config = new HashMap<>(), challenge = new HashMap<>();    final String raw = "a".repeat(43), hash = AuthService.hash(raw);

    @BeforeEach
    void setup() {
        config.put("enabled", true);
        config.put("last_step", -1L);
        config.put("encrypted_secret", "ciphertext");
        challenge.put("expires_at", Timestamp.from(Instant.now().plusSeconds(100)));
        challenge.put("consumed_at", null);
        when(jdbc.queryForList("SELECT username FROM auth.mfa_challenge WHERE token_hash=?", String.class, hash)).thenReturn(List.of("alice"));
        when(jdbc.queryForList("SELECT username FROM auth.app_user WHERE username=? FOR UPDATE", String.class, "alice")).thenReturn(List.of("alice"));
        when(jdbc.queryForMap("SELECT * FROM auth.mfa_config WHERE username=? FOR UPDATE", "alice")).thenReturn(config);
        when(jdbc.queryForMap("SELECT expires_at,consumed_at FROM auth.mfa_challenge WHERE token_hash=?", hash)).thenReturn(challenge);
        when(users.loadUserByUsername("alice")).thenReturn(User.withUsername("alice").password("hash").roles("USER").build());
        when(cipher.decrypt("ciphertext")).thenReturn("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
    }

    @Test
    void expiredOrConsumedChallengeCannotConsumeRecoveryCode() {
        challenge.put("consumed_at", Timestamp.from(Instant.now()));
        assertThrows(AuthFailure.class, () -> service.verifyChallenge(raw, null, "a".repeat(32)));
        challenge.put("consumed_at", null);
        challenge.put("expires_at", Timestamp.from(Instant.now().minusSeconds(1)));
        assertThrows(AuthFailure.class, () -> service.verifyChallenge(raw, null, "a".repeat(32)));
        verify(jdbc, never()).update(eq("UPDATE auth.mfa_recovery_code SET consumed_at=clock_timestamp() WHERE username=? AND code_hash=? AND consumed_at IS NULL"), anyString(), anyString());
    }

    @Test
    void successfulRecoveryConsumesHashAndChallenge() {
        String recovery = "12345678-90abcdef-12345678-90abcdef";
        String digest = AuthService.hash(recovery.replace("-", ""));
        when(jdbc.update("UPDATE auth.mfa_recovery_code SET consumed_at=clock_timestamp() WHERE username=? AND code_hash=? AND consumed_at IS NULL", "alice", digest)).thenReturn(1);
        var proof = service.verifyChallenge(raw, null, recovery);
        assertEquals("pwd recovery_code", proof.amr());
        verify(jdbc).update("UPDATE auth.mfa_challenge SET consumed_at=clock_timestamp() WHERE token_hash=?", hash);
    }

    @Test
    void enabledMfaCannotBeBypassedByMissingOrBothFactors() {
        assertThrows(AuthFailure.class, () -> service.verifyChallenge(raw, null, null));
        assertThrows(AuthFailure.class, () -> service.verifyChallenge(raw, "123456", "a".repeat(32)));
        verify(jdbc, times(2)).update("UPDATE auth.mfa_config SET failures=failures+1,blocked_until=CASE WHEN failures+1>=5 THEN clock_timestamp()+INTERVAL '5 minutes' ELSE NULL END WHERE username=?", "alice");
    }

    @Test
    void lockoutAppliesBeforeCheckingFactor() {
        config.put("blocked_until", Timestamp.from(Instant.now().plusSeconds(300)));
        assertEquals(429, assertThrows(AuthFailure.class, () -> service.verifyChallenge(raw, null, "a".repeat(32))).status().value());
        verifyNoInteractions(cipher);
    }

    @Test
    void outstandingChallengesAreBounded() {
        when(jdbc.queryForObject("SELECT count(*) FROM auth.mfa_challenge WHERE username=? AND consumed_at IS NULL AND expires_at>clock_timestamp()", Integer.class, "alice")).thenReturn(10);
        assertEquals(429, assertThrows(AuthFailure.class, () -> service.challenge("alice")).status().value());
    }

    @Test
    void cleanupRaceReturnsUnauthorizedInsteadOfServerError() {
        when(jdbc.queryForMap("SELECT expires_at,consumed_at FROM auth.mfa_challenge WHERE token_hash=?", hash))
                .thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));
        assertEquals(401, assertThrows(AuthFailure.class, () -> service.verifyChallenge(raw, null, "a".repeat(32))).status().value());
    }

    @Test
    void browserPasswordResetRaceRejectsStalePasswordProof() {
        when(jdbc.queryForObject("SELECT password_hash FROM auth.app_user WHERE username=?", String.class, "alice")).thenReturn("new-hash");
        assertEquals(401, assertThrows(AuthFailure.class, () -> service.browserFactor("alice", "123456", null, "old-hash")).status().value());
        verifyNoInteractions(cipher);
    }


}
