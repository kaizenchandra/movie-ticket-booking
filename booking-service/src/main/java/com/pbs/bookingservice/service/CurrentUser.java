package com.pbs.bookingservice.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {
    public long id(Authentication authentication) {
        String subject = authentication instanceof JwtAuthenticationToken jwt ? jwt.getToken().getSubject()
                : authentication.getPrincipal() instanceof OidcUser oidc ? oidc.getSubject() : authentication.getName();
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(subject.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.nio.ByteBuffer.wrap(digest).getLong() & Long.MAX_VALUE;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
