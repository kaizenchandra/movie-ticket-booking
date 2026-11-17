package com.pbs.authservice.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class MfaService {
    private final JdbcTemplate jdbc;
    private final MailCipher cipher;
    private final AuthenticationManager passwords;
    private final UserDetailsService users;
    private final BrowserSessions browsers;
    private final String issuer;
    private final SecureRandom random = new SecureRandom();

    public MfaService(JdbcTemplate jdbc, MailCipher cipher, AuthenticationManager passwords, UserDetailsService users,
                      BrowserSessions browsers, @Value("${auth.mfa-issuer:Movie Ticket Booking}") String issuer) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.passwords = passwords;
        this.users = users;
        this.browsers = browsers;
        this.issuer = issuer;
    }

    private String opaque() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private Map<String, Object> locked(String username) {
        if (jdbc.queryForList("SELECT username FROM auth.app_user WHERE username=? FOR UPDATE", String.class, username).isEmpty())
            throw invalid();
        jdbc.update("INSERT INTO auth.mfa_config(username) VALUES (?) ON CONFLICT (username) DO NOTHING", username);
        return jdbc.queryForMap("SELECT * FROM auth.mfa_config WHERE username=? FOR UPDATE", username);
    }

    private void throttle(Map<String, Object> config, String username) {
        Timestamp until = (Timestamp) config.get("blocked_until");
        if (until != null && until.toInstant().isAfter(Instant.now()))
            throw new AuthFailure(HttpStatus.TOO_MANY_REQUESTS, "Too many MFA attempts; try again later");
        if (until != null)
            jdbc.update("UPDATE auth.mfa_config SET failures=0,blocked_until=NULL WHERE username=?", username);
    }

    private AuthFailure invalid() {
        return new AuthFailure(HttpStatus.UNAUTHORIZED, "Invalid or expired MFA verification");
    }

    private void failed(String username) {
        jdbc.update("UPDATE auth.mfa_config SET failures=failures+1,blocked_until=CASE WHEN failures+1>=5 THEN clock_timestamp()+INTERVAL '5 minutes' ELSE NULL END WHERE username=?", username);
        throw invalid();
    }

    private void password(String username, String password) {
        if (password == null || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            failed(username);
        try {
            passwords.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username, password));
        } catch (org.springframework.security.core.AuthenticationException ex) {
            failed(username);
        }
    }

    @Transactional(noRollbackFor = AuthFailure.class)
    public Map<String, Object> setup(String username, String password) {
        var config = locked(username);
        throttle(config, username);
        password(username, password);
        if (Boolean.TRUE.equals(config.get("enabled")))
            throw new AuthFailure(HttpStatus.CONFLICT, "MFA is already enabled");
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        String secret = Totp.base32(bytes);
        jdbc.update("UPDATE auth.mfa_config SET pending_secret=?,pending_expires=clock_timestamp()+INTERVAL '10 minutes',failures=0,blocked_until=NULL WHERE username=?", cipher.encrypt(secret), username);
        String label = java.net.URLEncoder.encode(issuer + ":" + username, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        String encodedIssuer = java.net.URLEncoder.encode(issuer, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        return Map.of("secret", secret, "otpauth_uri", "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + encodedIssuer + "&algorithm=SHA1&digits=6&period=30", "expires_in", 600);
    }

    @Transactional(noRollbackFor = AuthFailure.class)
    public Map<String, Object> confirm(String username, String code) {
        var config = locked(username);
        throttle(config, username);
        Timestamp expires = (Timestamp) config.get("pending_expires");
        if (Boolean.TRUE.equals(config.get("enabled")) || expires == null || !expires.toInstant().isAfter(Instant.now()))
            throw invalid();
        var step = Totp.matchingStep(cipher.decrypt((String) config.get("pending_secret")), code, Instant.now(), -1);
        if (step.isEmpty()) failed(username);
        jdbc.update("UPDATE auth.mfa_config SET enabled=true,encrypted_secret=pending_secret,pending_secret=NULL,pending_expires=NULL,last_step=?,failures=0,blocked_until=NULL WHERE username=?", step.getAsLong(), username);
        List<String> codes = replaceRecovery(username);
        changed(username);
        return Map.of("enabled", true, "recovery_codes", codes);
    }

    @Transactional
    public Map<String, Object> status(String username) {
        var config = locked(username);
        Integer remaining = jdbc.queryForObject("SELECT count(*) FROM auth.mfa_recovery_code WHERE username=? AND consumed_at IS NULL", Integer.class, username);
        return Map.of("enabled", Boolean.TRUE.equals(config.get("enabled")), "recovery_codes_remaining", remaining == null ? 0 : remaining);
    }

    // Called only after the API password check, while the account row is locked.
    public Challenge challenge(String username) {
        var config = locked(username);
        if (!Boolean.TRUE.equals(config.get("enabled"))) return null;
        throttle(config, username);
        Integer active = jdbc.queryForObject("SELECT count(*) FROM auth.mfa_challenge WHERE username=? AND consumed_at IS NULL AND expires_at>clock_timestamp()", Integer.class, username);
        if (active != null && active >= 10)
            throw new AuthFailure(HttpStatus.TOO_MANY_REQUESTS, "Too many active MFA challenges; try again later");
        String raw = opaque();
        jdbc.update("INSERT INTO auth.mfa_challenge(token_hash,username,expires_at) VALUES (?,?,clock_timestamp()+INTERVAL '5 minutes')", AuthService.hash(raw), username);
        return new Challenge(true, raw, 300);
    }

    public Proof verifyChallenge(String raw, String code, String recoveryCode) {
        if (raw == null || !raw.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        String hash = AuthService.hash(raw);
        var names = jdbc.queryForList("SELECT username FROM auth.mfa_challenge WHERE token_hash=?", String.class, hash);
        if (names.isEmpty()) throw invalid();
        String username = names.getFirst();
        var config = locked(username);
        throttle(config, username);
        Map<String, Object> challenge;
        try {
            challenge = jdbc.queryForMap("SELECT expires_at,consumed_at FROM auth.mfa_challenge WHERE token_hash=?", hash);
        } catch (org.springframework.dao.EmptyResultDataAccessException expired) {
            throw invalid();
        }
        if (challenge.get("consumed_at") != null || !((Timestamp) challenge.get("expires_at")).toInstant().isAfter(Instant.now())
                || !users.loadUserByUsername(username).isEnabled()) throw invalid();
        String amr = factor(username, config, code, recoveryCode);
        jdbc.update("UPDATE auth.mfa_challenge SET consumed_at=clock_timestamp() WHERE token_hash=?", hash);
        return new Proof(username, amr);
    }

    private String factor(String username, Map<String, Object> config, String code, String recoveryCode) {
        if (!Boolean.TRUE.equals(config.get("enabled"))) throw invalid();
        boolean otp = code != null && !code.isBlank(), recovery = recoveryCode != null && !recoveryCode.isBlank();
        if (otp == recovery) failed(username);
        String amr;
        if (otp) {
            var step = Totp.matchingStep(cipher.decrypt((String) config.get("encrypted_secret")), code, Instant.now(), ((Number) config.get("last_step")).longValue());
            if (step.isEmpty()) failed(username);
            jdbc.update("UPDATE auth.mfa_config SET last_step=? WHERE username=?", step.getAsLong(), username);
            amr = "pwd otp";
        } else {
            String normalized = recoveryCode.replace("-", "").toLowerCase(Locale.ROOT);
            if (!normalized.matches("[0-9a-f]{32}")) failed(username);
            if (jdbc.update("UPDATE auth.mfa_recovery_code SET consumed_at=clock_timestamp() WHERE username=? AND code_hash=? AND consumed_at IS NULL", username, AuthService.hash(normalized)) != 1)
                failed(username);
            amr = "pwd recovery_code";
        }
        jdbc.update("UPDATE auth.mfa_config SET failures=0,blocked_until=NULL WHERE username=?", username);
        return amr;
    }

    @Transactional(noRollbackFor = AuthFailure.class)
    public String browserFactor(String username, String code, String recoveryCode, String passwordHash) {
        var config = locked(username);
        String current = jdbc.queryForObject("SELECT password_hash FROM auth.app_user WHERE username=?", String.class, username);
        if (current == null || passwordHash == null || !java.security.MessageDigest.isEqual(current.getBytes(java.nio.charset.StandardCharsets.UTF_8), passwordHash.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            throw invalid();
        if (!Boolean.TRUE.equals(config.get("enabled"))) return "pwd";
        throttle(config, username);
        return factor(username, config, code, recoveryCode);
    }

    @Transactional(noRollbackFor = AuthFailure.class)
    public Map<String, Object> regenerate(String username, String password, String code, String recoveryCode) {
        var config = locked(username);
        throttle(config, username);
        password(username, password);
        factor(username, config, code, recoveryCode);
        return Map.of("recovery_codes", replaceRecovery(username));
    }

    @Transactional(noRollbackFor = AuthFailure.class)
    public void disable(String username, String password, String code, String recoveryCode) {
        var config = locked(username);
        throttle(config, username);
        password(username, password);
        factor(username, config, code, recoveryCode);
        jdbc.update("UPDATE auth.mfa_config SET enabled=false,encrypted_secret=NULL,pending_secret=NULL,pending_expires=NULL,last_step=-1,failures=0,blocked_until=NULL WHERE username=?", username);
        jdbc.update("DELETE FROM auth.mfa_recovery_code WHERE username=?", username);
        changed(username);
    }

    private List<String> replaceRecovery(String username) {
        jdbc.update("DELETE FROM auth.mfa_recovery_code WHERE username=?", username);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            byte[] bytes = new byte[16];
            random.nextBytes(bytes);
            String raw = HexFormat.of().formatHex(bytes);
            jdbc.update("INSERT INTO auth.mfa_recovery_code(username,code_hash) VALUES (?,?)", username, AuthService.hash(raw));
            result.add(raw.substring(0, 8) + "-" + raw.substring(8, 16) + "-" + raw.substring(16, 24) + "-" + raw.substring(24));
        }
        return result;
    }

    private void changed(String username) {
        jdbc.update("UPDATE auth.app_user SET mfa_changed_at=clock_timestamp() WHERE username=?", username);
        jdbc.update("UPDATE auth.refresh_session SET revoked=true WHERE username=?", username);
        jdbc.update("UPDATE auth.mfa_challenge SET consumed_at=clock_timestamp() WHERE username=? AND consumed_at IS NULL", username);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                browsers.revoke(username);
            }
        });
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3600000)
    @Transactional
    public void cleanup() {
        jdbc.update("DELETE FROM auth.mfa_challenge WHERE expires_at<clock_timestamp()-INTERVAL '1 day'");
        jdbc.update("UPDATE auth.mfa_config SET pending_secret=NULL,pending_expires=NULL WHERE pending_expires<clock_timestamp()");
    }

    public record Challenge(boolean mfa_required, String challenge_token,
                            long expires_in) implements AuthService.LoginResult {
    }

    public record Proof(String username, String amr) {
    }
}
