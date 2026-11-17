package com.pbs.authservice.auth;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks this auth-service instance's browser sessions; API JWTs remain stateless.
 */
@Component
public class BrowserSessions implements HttpSessionListener, HttpSessionIdListener {
    private final ConcurrentHashMap<String, HttpSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void sessionCreated(HttpSessionEvent event) {
        sessions.put(event.getSession().getId(), event.getSession());
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        sessions.remove(event.getSession().getId());
    }

    @Override
    public void sessionIdChanged(HttpSessionEvent event, String oldId) {
        sessions.remove(oldId);
        sessions.put(event.getSession().getId(), event.getSession());
    }

    public void revoke(String username) {
        for (HttpSession session : sessions.values()) {
            try {
                Object stored = session.getAttribute("SPRING_SECURITY_CONTEXT");
                if (stored instanceof SecurityContext context && context.getAuthentication() != null
                        && username.equals(context.getAuthentication().getName())) session.invalidate();
            } catch (IllegalStateException ignored) { /* Already invalidated by another request. */ }
        }
    }
}
