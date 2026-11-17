package com.pbs.authservice.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class BrowserLoginController {
    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> login(HttpServletRequest request) {
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        String hidden = csrf == null ? "" : "<input type=\"hidden\" name=\"" + escape(csrf.getParameterName()) + "\" value=\"" + escape(csrf.getToken()) + "\">";
        String error = request.getParameter("error") == null ? "" : "<p role=\"alert\">Sign-in failed. Check your credentials and required second factor.</p>";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body("""
                <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Sign in</title>
                <style>body{font:18px system-ui;max-width:32rem;margin:4rem auto;padding:1rem}label{display:block;margin-top:1rem}input,button{font:inherit;padding:.6rem}button{margin-top:1rem}</style>
                <h1>Sign in</h1>
                """ + error + "<form method=\"post\" action=\"/login\">" + hidden + """
                <label>Username <input name="username" autocomplete="username" required></label>
                <label>Password <input name="password" type="password" autocomplete="current-password" required></label>
                <p>If MFA is enabled, enter an authenticator code or a recovery code.</p>
                <label>Authenticator code <input name="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6"></label>
                <label>Recovery code <input name="recoveryCode" autocomplete="off" maxlength="64"></label>
                <button>Sign in</button></form></html>
                """);
    }

    private String escape(String input) {
        return input.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
