package com.pbs.authservice.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class AuthService {
    private final MfaService mfa;
    private final EmailWorkflowService emailWorkflows;
    private final JdbcTemplate jdbc;
    private final AuthenticationManager authentication;
    private final UserDetailsService users;
    private final PasswordEncoder encoder;
    private final JwtEncoder jwt;
    private final String issuer;
    private final Duration accessTtl, refreshTtl;
    private final SecureRandom random = new SecureRandom();

    public AuthService(MfaService mfa, EmailWorkflowService emailWorkflows, JdbcTemplate jdbc, AuthenticationManager authentication, UserDetailsService users,
                       PasswordEncoder encoder, JwtEncoder jwt,
                       @Value("${spring.security.oauth2.authorizationserver.issuer}") String issuer,
                       @Value("${auth.access-ttl:15m}") Duration accessTtl,
                       @Value("${auth.refresh-ttl:7d}") Duration refreshTtl) {
        this.mfa = mfa;
        this.emailWorkflows = emailWorkflows;
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.issuer = issuer;
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
        if (accessTtl.isNegative() || accessTtl.isZero() || refreshTtl.compareTo(accessTtl) <= 0)
            throw new IllegalArgumentException("Refresh TTL must exceed a positive access TTL");
    }

    public static void validateNewPassword(String password) {
        if (password == null || password.length() < 12 || password.isBlank() || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new AuthFailure(HttpStatus.BAD_REQUEST, "Password must contain at least 12 characters and at most 72 UTF-8 bytes");
    }

    public static String normalize(String username) {
        if (username == null || !username.matches("[A-Za-z0-9_.-]{3,64}"))
            throw new AuthFailure(HttpStatus.BAD_REQUEST, "Username must contain 3 to 64 letters, numbers, dots, underscores or hyphens");
        return username.toLowerCase(Locale.ROOT);
    }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Transactional
    public Map<String, Object> register(String username, String password, String email) {
        username = normalize(username);
        validateNewPassword(password);
        return emailWorkflows.createUser(username, encoder.encode(password), email);
    }

    @Transactional(noRollbackFor = AuthFailure.class)
    public LoginResult login(String username, String password) {
        username = normalize(username);
        passwordLength(password);
        // Serialize login against password reset before reading the stored password.
        jdbc.queryForList("SELECT username FROM auth.app_user WHERE username=? FOR UPDATE", String.class, username);
        // Durable per-account throttling supplements deployment-level IP rate limiting.
        jdbc.update("INSERT INTO auth.login_attempt(username,failures) VALUES (?,0) ON CONFLICT (username) DO NOTHING", username);
        var attempt = jdbc.queryForMap("SELECT failures,blocked_until FROM auth.login_attempt WHERE username=? FOR UPDATE", username);
        Timestamp until = (Timestamp) attempt.get("blocked_until");
        if (until != null && until.toInstant().isAfter(Instant.now()))
            throw new AuthFailure(HttpStatus.TOO_MANY_REQUESTS, "Too many login attempts; try again later");
        if (until != null)
            jdbc.update("UPDATE auth.login_attempt SET failures=0,blocked_until=NULL WHERE username=?", username);
        try {
            authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username, password));
        } catch (org.springframework.security.core.AuthenticationException ex) {
            jdbc.update("UPDATE auth.login_attempt SET failures=failures+1,blocked_until=CASE WHEN failures+1>=5 THEN CURRENT_TIMESTAMP + INTERVAL '5 minutes' ELSE NULL END WHERE username=?", username);
            throw new AuthFailure(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        jdbc.update("DELETE FROM auth.login_attempt WHERE username=?", username);
        var challenge = mfa.challenge(username);
        if (challenge != null) return challenge;
        return startSession(username, "pwd");
    }

    @Transactional(noRollbackFor = AuthFailure.class)
    public TokenResponse completeMfa(String challenge, String code, String recoveryCode) {
        var proof = mfa.verifyChallenge(challenge, code, recoveryCode);
        return startSession(proof.username(), proof.amr());
    }

    private TokenResponse startSession(String username, String amr) {
        UUID session = UUID.randomUUID();
        Instant expiry = Instant.now().plus(refreshTtl);
        jdbc.update("INSERT INTO auth.refresh_session(id,username,expires_at,revoked,amr) VALUES (?,?,?,false,?)", session, username, Timestamp.from(expiry), amr);
        return issue(username, session, expiry, amr);
    }

    @Transactional(noRollbackFor = AuthFailure.class)
    public TokenResponse refresh(String raw) {
        StoredToken token = token(raw);
        var owner = jdbc.queryForList("SELECT username FROM auth.refresh_session WHERE id=?", String.class, token.session());
        if (owner.isEmpty()) throw invalidRefresh();
        jdbc.queryForList("SELECT username FROM auth.app_user WHERE username=? FOR UPDATE", String.class, owner.getFirst());
        Session session = session(token.session());
        token = token(raw); // Re-read after locking the family to serialize rotation and revocation.
        if (session.revoked() || !session.expires().isAfter(Instant.now())) throw invalidRefresh();
        if (token.consumed() != null) {
            jdbc.update("UPDATE auth.refresh_session SET revoked=true WHERE id=?", token.session());
            throw invalidRefresh(); // Commit family revocation even though this request returns 401.
        }
        var user = users.loadUserByUsername(session.username());
        if (!user.isEnabled()) throw invalidRefresh();
        jdbc.update("UPDATE auth.refresh_token SET consumed_at=CURRENT_TIMESTAMP WHERE token_hash=?", hash(raw));
        return issue(session.username(), token.session(), session.expires(), session.amr());
    }

    @Transactional
    public void logout(String raw) {
        if (raw == null || raw.length() > 512) return;
        var matches = jdbc.query("SELECT session_id FROM auth.refresh_token WHERE token_hash=?", (rs, n) -> rs.getObject(1, UUID.class), hash(raw));
        if (!matches.isEmpty()) {
            session(matches.getFirst());
            jdbc.update("UPDATE auth.refresh_session SET revoked=true WHERE id=?", matches.getFirst());
        }
    }

    @Transactional
    public void logoutAll(String username) {
        jdbc.queryForList("SELECT username FROM auth.app_user WHERE username=? FOR UPDATE", String.class, username);
        jdbc.update("UPDATE auth.refresh_session SET revoked=true WHERE username=?", username);
        jdbc.update("UPDATE auth.mfa_challenge SET consumed_at=clock_timestamp() WHERE username=? AND consumed_at IS NULL", username);
    }

    public Map<String, Object> me(String username) {
        var user = users.loadUserByUsername(username);
        if (!user.isEnabled()) throw new AuthFailure(HttpStatus.UNAUTHORIZED, "Account is disabled");
        return Map.of("username", user.getUsername(), "roles", List.of("USER"));
    }

    private TokenResponse issue(String username, UUID session, Instant refreshExpiry, String amr) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(issuer).subject(username).audience(List.of("booking-service", "auth-service"))
                .issuedAt(now).expiresAt(now.plus(accessTtl)).id(UUID.randomUUID().toString())
                .claim("amr", List.of(amr.split(" "))).claim("scope", "profile read write").claim("roles", List.of("USER")).build();
        String access = jwt.encode(JwtEncoderParameters.from(JwsHeader.with(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        jdbc.update("INSERT INTO auth.refresh_token(token_hash,session_id) VALUES (?,?)", hash(refresh), session);
        return new TokenResponse(access, refresh, "Bearer", accessTtl.toSeconds(), Math.max(0, Duration.between(now, refreshExpiry).toSeconds()), "profile read write");
    }

    private StoredToken token(String raw) {
        if (raw == null || raw.length() < 32 || raw.length() > 512) throw invalidRefresh();
        return jdbc.query("SELECT session_id,consumed_at FROM auth.refresh_token WHERE token_hash=?",
                        (rs, n) -> new StoredToken(rs.getObject(1, UUID.class), rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant()), hash(raw))
                .stream().findFirst().orElseThrow(this::invalidRefresh);
    }

    private Session session(UUID id) {
        return jdbc.query("SELECT username,expires_at,revoked,amr FROM auth.refresh_session WHERE id=? FOR UPDATE",
                (rs, n) -> new Session(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getBoolean(3), rs.getString(4)), id).stream().findFirst().orElseThrow(this::invalidRefresh);
    }

    private AuthFailure invalidRefresh() {
        return new AuthFailure(HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token");
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3600000)
    @Transactional
    public void cleanExpiredSessions() {
        // Retain consumed-token history through the family lifetime for replay detection.
        jdbc.update("DELETE FROM auth.refresh_session WHERE expires_at < CURRENT_TIMESTAMP - INTERVAL '1 day'");
    }

    private void passwordLength(String password) {
        if (password == null || password.isBlank() || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new AuthFailure(HttpStatus.BAD_REQUEST, "Password must contain 1 to 72 UTF-8 bytes");
    }

    public interface LoginResult {
    }

    public record TokenResponse(String access_token, String refresh_token, String token_type,
                                long expires_in, long refresh_expires_in, String scope) implements LoginResult {
    }

    private record StoredToken(UUID session, Instant consumed) {
    }

    private record Session(String username, Instant expires, boolean revoked, String amr) {
    }
}
