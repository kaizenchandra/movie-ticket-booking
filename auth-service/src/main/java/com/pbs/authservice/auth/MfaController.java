package com.pbs.authservice.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth/mfa")
public class MfaController {
    private final MfaService mfa;
    private final AuthService auth;

    public MfaController(MfaService mfa, AuthService auth) {
        this.mfa = mfa;
        this.auth = auth;
    }

    private <T> ResponseEntity<T> secret(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(value);
    }

    @GetMapping
    public Map<String, Object> status(JwtAuthenticationToken user) {
        return mfa.status(user.getToken().getSubject());
    }

    @PostMapping("/setup")
    public ResponseEntity<?> setup(JwtAuthenticationToken user, @Valid @RequestBody Password body) {
        return secret(mfa.setup(user.getToken().getSubject(), body.password()));
    }

    @PostMapping("/confirm")
    public ResponseEntity<?> confirm(JwtAuthenticationToken user, @Valid @RequestBody Code body) {
        return secret(mfa.confirm(user.getToken().getSubject(), body.code()));
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verify(@Valid @RequestBody Verify body) {
        return secret(auth.completeMfa(body.challenge_token(), body.code(), body.recovery_code()));
    }

    @PostMapping("/recovery-codes")
    public ResponseEntity<?> recovery(JwtAuthenticationToken user, @Valid @RequestBody Management body) {
        return secret(mfa.regenerate(user.getToken().getSubject(), body.password(), body.code(), body.recovery_code()));
    }

    @PostMapping("/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(JwtAuthenticationToken user, @Valid @RequestBody Management body) {
        mfa.disable(user.getToken().getSubject(), body.password(), body.code(), body.recovery_code());
    }

    public record Password(@NotBlank @Size(max = 72) String password) {
    }

    public record Code(@NotBlank @Size(max = 6) String code) {
    }

    public record Verify(@NotBlank @Size(max = 128) String challenge_token, @Size(max = 6) String code,
                         @Size(max = 64) String recovery_code) {
    }

    public record Management(@NotBlank @Size(max = 72) String password, @Size(max = 6) String code,
                             @Size(max = 64) String recovery_code) {
    }
}
