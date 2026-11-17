package com.pbs.authservice.auth;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Browser authentication succeeds only after both required factors are checked.
 */
public class BrowserMfaProvider extends DaoAuthenticationProvider {
    private final MfaService mfa;

    public BrowserMfaProvider(UserDetailsService users, PasswordEncoder encoder, MfaService mfa) {
        super(users);
        setPasswordEncoder(encoder);
        this.mfa = mfa;
    }

    @Override
    protected void additionalAuthenticationChecks(UserDetails user, UsernamePasswordAuthenticationToken authentication) {
        super.additionalAuthenticationChecks(user, authentication);
        MfaWebDetails details = authentication.getDetails() instanceof MfaWebDetails web ? web : null;
        try {
            String amr = mfa.browserFactor(user.getUsername(), details == null ? null : details.code(), details == null ? null : details.recoveryCode(), user.getPassword());
            if (details != null) details.verified(amr);
        } catch (AuthFailure failure) {
            throw new BadCredentialsException("Invalid credentials");
        } finally {
            if (details != null) details.erase();
        }
    }
}
