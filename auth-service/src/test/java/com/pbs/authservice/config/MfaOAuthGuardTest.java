package com.pbs.authservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MfaOAuthGuardTest {
    void invoke(boolean mfa, List<String> amr, Instant issued, Instant changed) {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList("SELECT GREATEST(password_changed_at,mfa_changed_at) FROM auth.app_user WHERE username=? FOR UPDATE", Timestamp.class, "alice"))
                .thenReturn(List.of(Timestamp.from(changed)));
        when(jdbc.queryForList("SELECT enabled FROM auth.mfa_config WHERE username=?", Boolean.class, "alice")).thenReturn(List.of(mfa));
        var jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject("alice").issuedAt(issued).expiresAt(issued.plusSeconds(900)).claim("amr", amr).build();
        var context = mock(JwtEncodingContext.class);
        when(context.getPrincipal()).thenReturn(new JwtAuthenticationToken(jwt));
        when(context.getAuthorization()).thenReturn(mock(OAuth2Authorization.class));
        when(context.getAuthorizationGrantType()).thenReturn(AuthorizationGrantType.AUTHORIZATION_CODE);
        when(context.getClaims()).thenReturn(JwtClaimsSet.builder());
        new WebSecurityConfig().passwordChangeGuard(jdbc).customize(context);
    }

    @Test
    void passwordOnlyBearerCannotMintNewGrantAfterMfaEnabled() {
        Instant now = Instant.now();
        assertThrows(OAuth2AuthenticationException.class, () -> invoke(true, List.of("pwd"), now, now.minusSeconds(60)));
    }

    @Test
    void freshMfaAuthenticatedBearerCanMintGrant() {
        Instant now = Instant.now();
        assertDoesNotThrow(() -> invoke(true, List.of("pwd", "otp"), now, now.minusSeconds(60)));
        assertDoesNotThrow(() -> invoke(true, List.of("pwd", "recovery_code"), now, now.minusSeconds(60)));
    }

    @Test
    void oldBearerCannotBypassPasswordOrMfaChangeThroughNewAuthorizationCode() {
        Instant now = Instant.now();
        assertThrows(OAuth2AuthenticationException.class, () -> invoke(false, List.of("pwd"), now.minusSeconds(120), now));
        assertThrows(OAuth2AuthenticationException.class, () -> invoke(true, List.of("pwd", "otp"), now.minusSeconds(120), now));
    }
}
