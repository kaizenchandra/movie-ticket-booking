package com.pbs.authservice.auth;

import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
@DependsOnDatabaseInitialization
public class MailCipher {
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public MailCipher(JdbcTemplate jdbc) {
        byte[] candidate = new byte[32];
        random.nextBytes(candidate);
        jdbc.update("INSERT INTO auth.mail_key(id,secret) VALUES (1,?) ON CONFLICT (id) DO NOTHING", Base64.getEncoder().encodeToString(candidate));
        key = Base64.getDecoder().decode(jdbc.queryForObject("SELECT secret FROM auth.mail_key WHERE id=1", String.class));
    }

    public String encrypt(String text) {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, nonce, text.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(nonce) + ":" + Base64.getEncoder().encodeToString(encrypted);
    }

    public String decrypt(String encrypted) {
        String[] parts = encrypted.split(":", 2);
        return new String(crypt(Cipher.DECRYPT_MODE, Base64.getDecoder().decode(parts[0]), Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
    }

    private byte[] crypt(int mode, byte[] nonce, byte[] value) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            return cipher.doFinal(value);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to process encrypted mail", ex);
        }
    }
}
