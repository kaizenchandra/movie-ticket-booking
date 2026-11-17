package com.pbs.authservice.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class EmailWorkflowServiceTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final PasswordEncoder encoder = mock(PasswordEncoder.class);
    final MailOutbox mail = mock(MailOutbox.class);
    final BrowserSessions sessions = mock(BrowserSessions.class);
    final EmailWorkflowService service = new EmailWorkflowService(sessions, jdbc, encoder, mail, "http://localhost:9000");
    final Map<String, Object> action = new HashMap<>();    final String token = "a".repeat(43), digest = AuthService.hash(token);

    @BeforeEach
    void setup() {
        TransactionSynchronizationManager.initSynchronization();
        action.put("expires_at", Timestamp.from(Instant.now().plusSeconds(600)));
        action.put("consumed_at", null);
        when(jdbc.queryForList("SELECT username FROM auth.account_action WHERE token_hash=? AND purpose=?", String.class, digest, "VERIFY_EMAIL")).thenReturn(List.of("alice"));
        when(jdbc.queryForList("SELECT username FROM auth.account_action WHERE token_hash=? AND purpose=?", String.class, digest, "RESET_PASSWORD")).thenReturn(List.of("alice"));
        when(jdbc.queryForMap("SELECT enabled FROM auth.app_user WHERE username=? FOR UPDATE", "alice")).thenReturn(Map.of("enabled", true));
        when(jdbc.queryForMap(eq("SELECT expires_at,consumed_at FROM auth.account_action WHERE token_hash=? AND purpose=?"), eq(digest), anyString())).thenReturn(action);
    }

    @AfterEach
    void clear() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void verificationConsumesHashedTokenAndEnablesEmail() {
        service.verify(token);
        verify(jdbc).update("UPDATE auth.account_action SET consumed_at=CURRENT_TIMESTAMP WHERE token_hash=?", digest);
        verify(jdbc).update("UPDATE auth.app_user SET email_verified=true WHERE username=?", "alice");
    }

    @Test
    void expiredOrUsedTokenCannotVerify() {
        action.put("expires_at", Timestamp.from(Instant.now().minusSeconds(1)));
        assertThrows(AuthFailure.class, () -> service.verify(token));
        action.put("expires_at", Timestamp.from(Instant.now().plusSeconds(100)));
        action.put("consumed_at", Timestamp.from(Instant.now()));
        assertThrows(AuthFailure.class, () -> service.verify(token));
        verify(jdbc, never()).update(eq("UPDATE auth.app_user SET email_verified=true WHERE username=?"), anyString());
    }

    @Test
    void resetRevokesSessionsAndDefersBrowserInvalidationUntilCommit() {
        when(encoder.encode("new-password-123")).thenReturn("bcrypt-hash");
        when(jdbc.queryForObject("SELECT email FROM auth.app_user WHERE username=?", String.class, "alice")).thenReturn("alice@example.test");
        service.reset(token, "new-password-123");
        verify(jdbc).update("UPDATE auth.app_user SET password_hash=?,password_changed_at=clock_timestamp() WHERE username=?", "bcrypt-hash", "alice");
        verify(jdbc).update("UPDATE auth.refresh_session SET revoked=true WHERE username=?", "alice");
        verifyNoInteractions(sessions);
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        verify(sessions).revoke("alice");
    }

    @Test
    void expiredResetCannotHashOrChangePassword() {
        action.put("expires_at", Timestamp.from(Instant.now().minusSeconds(1)));
        assertThrows(AuthFailure.class, () -> service.reset(token, "new-password-123"));
        verifyNoInteractions(encoder, mail, sessions);
    }

    @Test
    void unknownEmailAndVerifiedResendDoNotSendMail() {
        when(jdbc.queryForList("SELECT username,email_verified,enabled FROM auth.app_user WHERE email=? FOR UPDATE", "alice@example.test"))
                .thenReturn(List.of(Map.of("username", "alice", "email_verified", true, "enabled", true)));
        service.request("unknown@example.test", "RESET_PASSWORD");
        service.request("alice@example.test", "VERIFY_EMAIL");
        verifyNoInteractions(mail);
    }


}
