package com.pbs.authservice.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MailOutbox {
    private final JdbcTemplate jdbc;
    private final JavaMailSender sender;
    private final MailCipher cipher;
    private final String from;

    public MailOutbox(JdbcTemplate jdbc, JavaMailSender sender, MailCipher cipher, @Value("${auth.mail-from:no-reply@movie-booking.test}") String from) {
        this.jdbc = jdbc;
        this.sender = sender;
        this.cipher = cipher;
        this.from = from;
    }

    public void queue(String email, String subject, String body) {
        jdbc.update("INSERT INTO auth.mail_outbox(recipient,subject,encrypted_body) VALUES (?,?,?)", email, subject, cipher.encrypt(body));
    }

    @Scheduled(fixedDelay = 3000)
    @Transactional
    public void deliver() {
        var messages = jdbc.queryForList("SELECT id,recipient,subject,encrypted_body FROM auth.mail_outbox WHERE status='PENDING' AND next_attempt<=CURRENT_TIMESTAMP ORDER BY id LIMIT 20 FOR UPDATE SKIP LOCKED");
        for (var row : messages) {
            try {
                var mail = new SimpleMailMessage();
                mail.setFrom(from);
                mail.setTo((String) row.get("recipient"));
                mail.setSubject((String) row.get("subject"));
                mail.setText(cipher.decrypt((String) row.get("encrypted_body")));
                sender.send(mail);
                jdbc.update("UPDATE auth.mail_outbox SET status='SENT',encrypted_body=NULL WHERE id=?", row.get("id"));
            } catch (RuntimeException ex) {
                // Persist retries without logging addresses, passwords, links or SMTP credentials.
                jdbc.update("UPDATE auth.mail_outbox SET attempts=attempts+1,next_attempt=CURRENT_TIMESTAMP+INTERVAL '30 seconds' WHERE id=?", row.get("id"));
            }
        }
    }
}
