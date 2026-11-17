package com.pbs.authservice.auth;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    final UUID session = UUID.randomUUID();
    final AuthenticationManager manager = mock(AuthenticationManager.class);
    final UserDetailsService users = mock(UserDetailsService.class);
    final PasswordEncoder encoder = mock(PasswordEncoder.class);
    final MfaService mfa = mock(MfaService.class);
    final JwtEncoder jwt = mock(JwtEncoder.class);
    final JdbcTemplate jdbc = mock(JdbcTemplate.class, invocation -> {
        if (invocation.getMethod().getName().equals("queryForList")) return List.of("alice");
        if (invocation.getMethod().getName().equals("queryForMap")) return Map.of("failures", 0);
        if (invocation.getMethod().getName().equals("query")) {
            String sql = invocation.getArgument(0);
            ResultSet rs = mock(ResultSet.class);
            if (sql.contains("FROM auth.refresh_token")) {
                when(rs.getObject(1, UUID.class)).thenReturn(session);
                when(rs.getTimestamp(2)).thenReturn(consumed ? Timestamp.from(Instant.now().minusSeconds(2)) : null);
            } else {
                when(rs.getString(1)).thenReturn("alice");
                when(rs.getTimestamp(2)).thenReturn(Timestamp.from(expires));
                when(rs.getBoolean(3)).thenReturn(false);
                when(rs.getString(4)).thenReturn("pwd");
            }
            RowMapper<?> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(rs, 0));
        }
        return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    final AuthService service = new AuthService(mfa, mock(EmailWorkflowService.class), jdbc, manager, users, encoder, jwt, "http://localhost:9000", Duration.ofMinutes(15), Duration.ofDays(7));
    boolean consumed = false;
    Instant expires = Instant.now().plusSeconds(3600);

    AuthServiceTest() {
        when(users.loadUserByUsername("alice")).thenReturn(User.withUsername("alice").password("hashed").roles("USER").build());
        when(jwt.encode(any())).thenReturn(Jwt.withTokenValue("signed-token").header("alg", "RS256").subject("alice").build());
    }

    @Test
    void loginStoresOnlyHashedRefreshAndReturnsBoundedLifetimes() {
        var tokens = (AuthService.TokenResponse) service.login("Alice", "long-enough-password");
        assertEquals("Bearer", tokens.token_type());
        assertEquals(900, tokens.expires_in());
        assertTrue(tokens.refresh_expires_in() <= 604800);
        assertEquals(43, tokens.refresh_token().length());
        verify(jdbc).update(eq("INSERT INTO auth.refresh_token(token_hash,session_id) VALUES (?,?)"), eq(AuthService.hash(tokens.refresh_token())), any(UUID.class));
        assertNotEquals(tokens.refresh_token(), AuthService.hash(tokens.refresh_token()));
    }

    @Test
    void reusedRefreshRevokesEntireFamilyWithoutIssuingTokens() {
        consumed = true;
        assertEquals(401, assertThrows(AuthFailure.class, () -> service.refresh("a".repeat(43))).status().value());
        verify(jdbc).update("UPDATE auth.refresh_session SET revoked=true WHERE id=?", session);
        verify(jwt, never()).encode(any());
    }

    @Test
    void expiredRefreshCannotIssueTokens() {
        expires = Instant.now().minusSeconds(1);
        assertEquals(401, assertThrows(AuthFailure.class, () -> service.refresh("a".repeat(43))).status().value());
        verify(jwt, never()).encode(any());
    }

    @Test
    void rotationConsumesOldTokenAndStoresNewHash() {
        String old = "a".repeat(43);
        var tokens = service.refresh(old);
        assertNotEquals(old, tokens.refresh_token());
        var order = inOrder(jdbc);
        order.verify(jdbc).update("UPDATE auth.refresh_token SET consumed_at=CURRENT_TIMESTAMP WHERE token_hash=?", AuthService.hash(old));
        order.verify(jdbc).update("INSERT INTO auth.refresh_token(token_hash,session_id) VALUES (?,?)", AuthService.hash(tokens.refresh_token()), session);
    }

    @Test
    void registrationRejectsWeakPasswordsAndBcryptByteOverflow() {
        assertEquals(400, assertThrows(AuthFailure.class, () -> service.register("alice", "short", "alice@example.test")).status().value());
        assertEquals(400, assertThrows(AuthFailure.class, () -> service.register("alice", "界".repeat(25), "alice@example.test")).status().value());
        verifyNoInteractions(encoder);
    }

    @Test
    void passwordOnlyMfaLoginReturnsChallengeWithoutIssuingTokens() {
        var challenge = new MfaService.Challenge(true, "challenge", 300);
        when(mfa.challenge("alice")).thenReturn(challenge);
        assertSame(challenge, service.login("alice", "long-enough-password"));
        verify(jwt, never()).encode(any());
        verify(jdbc, never()).update(eq("INSERT INTO auth.refresh_session(id,username,expires_at,revoked,amr) VALUES (?,?,?,false,?)"), any(), any(), any(), any());
    }
}
