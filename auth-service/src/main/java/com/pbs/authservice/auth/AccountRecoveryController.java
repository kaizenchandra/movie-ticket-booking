package com.pbs.authservice.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AccountRecoveryController {
    private final EmailWorkflowService service;

    public AccountRecoveryController(EmailWorkflowService service) {
        this.service = service;
    }

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> forgot(@Valid @RequestBody EmailRequest request) {
        service.request(request.email(), "RESET_PASSWORD");
        return accepted();
    }

    @PostMapping("/email-verification/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> resend(@Valid @RequestBody EmailRequest request) {
        service.request(request.email(), "VERIFY_EMAIL");
        return accepted();
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verify(@Valid @RequestBody TokenRequest request) {
        service.verify(request.token());
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@Valid @RequestBody ResetRequest request) {
        service.reset(request.token(), request.newPassword());
    }

    private Map<String, String> accepted() {
        return Map.of("message", "If the account is eligible, an email will be sent.");
    }

    public record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {
    }

    public record TokenRequest(@NotBlank @Size(max = 512) String token) {
    }

    public record ResetRequest(@NotBlank @Size(max = 512) String token, @NotBlank @Size(max = 72) String newPassword) {
    }
}
