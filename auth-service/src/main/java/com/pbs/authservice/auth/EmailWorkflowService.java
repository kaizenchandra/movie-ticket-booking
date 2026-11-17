package com.pbs.authservice.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

@Service
public class EmailWorkflowService {
    private final BrowserSessions browserSessions;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final MailOutbox mail;
    private final String baseUrl;
    private final SecureRandom random = new SecureRandom();

    public EmailWorkflowService(BrowserSessions browserSessions, JdbcTemplate jdbc, PasswordEncoder encoder, MailOutbox mail,
                                @Value("${auth.public-base-url:http://localhost:9000}") String baseUrl) {
        this.browserSessions = browserSessions;
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.mail = mail;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        if (!baseUrl.matches("https?://[^\\s]+")) throw new IllegalArgumentException("Invalid auth public base URL");
    }

    public static String normalizeEmail(String email) {
        if (email == null || email.length() > 254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || email.indexOf('\r') >= 0 || email.indexOf('\n') >= 0)
            throw new AuthFailure(HttpStatus.BAD_REQUEST, "A valid email address is required");
        return email.toLowerCase(Locale.ROOT);
    }

    @Transactional
    public Map<String, Object> createUser(String username, String passwordHash, String email) {
        email = normalizeEmail(email);
        if (jdbc.update("INSERT INTO auth.app_user(username,password_hash,enabled,email,email_verified) VALUES (?,?,true,?,false) ON CONFLICT DO NOTHING", username, passwordHash, email) == 0)
            throw new AuthFailure(HttpStatus.CONFLICT, "Username or email is already registered");
        issue(username, email, "VERIFY_EMAIL");
        return Map.of("username", username, "email", email, "email_verified", false);
    }

    @Transactional
    public void request(String email, String purpose) {
        email = normalizeEmail(email);
        var matches = jdbc.queryForList("SELECT username,email_verified,enabled FROM auth.app_user WHERE email=? FOR UPDATE", email);
        if (matches.isEmpty()) return;
        var user = matches.getFirst();
        if (!Boolean.TRUE.equals(user.get("enabled")) || (purpose.equals("VERIFY_EMAIL") && Boolean.TRUE.equals(user.get("email_verified"))))
            return;
        String username = (String) user.get("username");
        Integer recent = jdbc.queryForObject("SELECT count(*) FROM auth.account_action WHERE username=? AND purpose=? AND created_at>CURRENT_TIMESTAMP-INTERVAL '60 seconds'", Integer.class, username, purpose);
        if (recent != null && recent > 0) return;
        issue(username, email, purpose);
    }

    private void issue(String username, String email, String purpose) {
        jdbc.update("UPDATE auth.account_action SET consumed_at=CURRENT_TIMESTAMP WHERE username=? AND purpose=? AND consumed_at IS NULL", username, purpose);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Duration ttl = purpose.equals("VERIFY_EMAIL") ? Duration.ofHours(24) : Duration.ofMinutes(30);
        jdbc.update("INSERT INTO auth.account_action(token_hash,username,purpose,expires_at) VALUES (?,?,?,?)", AuthService.hash(token), username, purpose, Timestamp.from(Instant.now().plus(ttl)));
        String path = purpose.equals("VERIFY_EMAIL") ? "/verify-email.html" : "/reset-password.html";
        mail.queue(email, purpose.equals("VERIFY_EMAIL") ? "Verify your email" : "Reset your password",
                "Open this link to " + (purpose.equals("VERIFY_EMAIL") ? "verify your email" : "reset your password") + ":\n" + baseUrl + path + "#token=" + token + "\nThis link expires in " + ttl.toMinutes() + " minutes. If you did not request this, ignore this message.");
    }

    @Transactional
    public void verify(String raw) {
        String username = consume(raw, "VERIFY_EMAIL");
        jdbc.update("UPDATE auth.app_user SET email_verified=true WHERE username=?", username);
    }

    @Transactional
    public void reset(String raw, String password) {
        AuthService.validateNewPassword(password);
        String username = consume(raw, "RESET_PASSWORD");
        String encoded = encoder.encode(password);
        jdbc.update("UPDATE auth.app_user SET password_hash=?,password_changed_at=clock_timestamp() WHERE username=?", encoded, username);
        jdbc.update("UPDATE auth.refresh_session SET revoked=true WHERE username=?", username);
        jdbc.update("UPDATE auth.mfa_challenge SET consumed_at=clock_timestamp() WHERE username=? AND consumed_at IS NULL", username);
        jdbc.update("DELETE FROM auth.login_attempt WHERE username=?", username);
        jdbc.update("UPDATE auth.account_action SET consumed_at=CURRENT_TIMESTAMP WHERE username=? AND purpose='RESET_PASSWORD' AND consumed_at IS NULL", username);
        String email = jdbc.queryForObject("SELECT email FROM auth.app_user WHERE username=?", String.class, username);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
            @Override
            public void afterCommit() {
                browserSessions.revoke(username);
            }
        });
        mail.queue(email, "Your password was changed", "Your password was changed. If you did not make this change, contact support immediately.");
    }

    private String consume(String raw, String purpose) {
        if (raw == null || !raw.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        String digest = AuthService.hash(raw);
        var users = jdbc.queryForList("SELECT username FROM auth.account_action WHERE token_hash=? AND purpose=?", String.class, digest, purpose);
        if (users.isEmpty()) throw invalid();
        String username = users.getFirst();
        var account = jdbc.queryForMap("SELECT enabled FROM auth.app_user WHERE username=? FOR UPDATE", username);
        var action = jdbc.queryForMap("SELECT expires_at,consumed_at FROM auth.account_action WHERE token_hash=? AND purpose=?", digest, purpose);
        if (!Boolean.TRUE.equals(account.get("enabled")) || action.get("consumed_at") != null
                || !((Timestamp) action.get("expires_at")).toInstant().isAfter(Instant.now())) throw invalid();
        jdbc.update("UPDATE auth.account_action SET consumed_at=CURRENT_TIMESTAMP WHERE token_hash=?", digest);
        return username;
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3600000)
    @Transactional
    public void cleanExpiredActions() {
        jdbc.update("DELETE FROM auth.account_action WHERE expires_at<CURRENT_TIMESTAMP-INTERVAL '1 day'");
        jdbc.update("DELETE FROM auth.mail_outbox WHERE status='SENT' AND created_at<CURRENT_TIMESTAMP-INTERVAL '7 days'");
    }

    private AuthFailure invalid() {
        return new AuthFailure(HttpStatus.BAD_REQUEST, "Invalid or expired action token");
    }
}
