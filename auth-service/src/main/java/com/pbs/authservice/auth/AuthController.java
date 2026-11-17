package com.pbs.authservice.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody Registration request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(auth.register(request.username(), request.password(), request.email()));
    }

    @PostMapping({"/login", "/token"})
    public ResponseEntity<AuthService.LoginResult> login(@Valid @RequestBody Credentials request) {
        var result = auth.login(request.username(), request.password());
        return ResponseEntity.status(result instanceof MfaService.Challenge ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(result);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthService.TokenResponse> refresh(@Valid @RequestBody Refresh request) {
        return tokens(auth.refresh(request.refresh_token()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody Refresh request) {
        auth.logout(request.refresh_token());
    }

    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutAll(JwtAuthenticationToken user) {
        auth.logoutAll(user.getToken().getSubject());
    }

    @GetMapping("/me")
    public Map<String, Object> me(JwtAuthenticationToken user) {
        return auth.me(user.getToken().getSubject());
    }

    private ResponseEntity<AuthService.TokenResponse> tokens(AuthService.TokenResponse response) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(response);
    }

    public record Credentials(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 72) String password) {
    }

    public record Registration(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 72) String password,
                               @NotBlank @Email @Size(max = 254) String email) {
    }

    public record Refresh(@NotBlank @Size(max = 512) String refresh_token) {
    }
}
