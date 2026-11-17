package com.pbs.authservice.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

@Configuration
public class WebSecurityConfig {
    @Bean
    org.springframework.boot.web.servlet.ServletListenerRegistrationBean<com.pbs.authservice.auth.BrowserSessions> browserSessionListener(com.pbs.authservice.auth.BrowserSessions sessions) {
        return new org.springframework.boot.web.servlet.ServletListenerRegistrationBean<>(sessions);
    }

    @Bean
    org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer<org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext> passwordChangeGuard(JdbcTemplate jdbc) {
        return context -> {
            var authorization = context.getAuthorization();
            if (authorization == null) return;
            Object details = context.getPrincipal().getDetails();
            java.util.List<String> amr = java.util.List.of("pwd");
            if (details instanceof com.pbs.authservice.auth.MfaWebDetails web)
                amr = java.util.List.of(web.amr().split(" "));
            Object principal = context.getPrincipal().getPrincipal();
            if (principal instanceof Jwt bearer && bearer.getClaimAsStringList("amr") != null)
                amr = bearer.getClaimAsStringList("amr");
            var changed = jdbc.queryForList("SELECT GREATEST(password_changed_at,mfa_changed_at) FROM auth.app_user WHERE username=? FOR UPDATE", java.sql.Timestamp.class, context.getPrincipal().getName());
            var enabled = jdbc.queryForList("SELECT enabled FROM auth.mfa_config WHERE username=?", Boolean.class, context.getPrincipal().getName());
            if (!enabled.isEmpty() && Boolean.TRUE.equals(enabled.getFirst()) && !amr.contains("otp") && !amr.contains("recovery_code"))
                throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException("invalid_grant");
            context.getClaims().claim("amr", amr);
            if (changed.isEmpty() || changed.getFirst() == null) return;
            if (principal instanceof Jwt bearer && (bearer.getIssuedAt() == null || bearer.getIssuedAt().getEpochSecond() < changed.getFirst().toInstant().getEpochSecond()))
                throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException("invalid_grant");
            java.time.Instant issued = null;
            if (org.springframework.security.oauth2.core.AuthorizationGrantType.REFRESH_TOKEN.equals(context.getAuthorizationGrantType()) && authorization.getRefreshToken() != null)
                issued = authorization.getRefreshToken().getToken().getIssuedAt();
            if (org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE.equals(context.getAuthorizationGrantType()) && authorization.getToken(org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode.class) != null)
                issued = authorization.getToken(org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode.class).getToken().getIssuedAt();
            if (issued != null && issued.isBefore(changed.getFirst().toInstant()))
                throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException("invalid_grant");
        };
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService users(JdbcTemplate jdbc) {
        return username -> jdbc.query("SELECT username,password_hash,enabled,email_verified FROM auth.app_user WHERE username = ?",
                        (rs, n) -> User.withUsername(rs.getString("username")).password(rs.getString("password_hash"))
                                .disabled(!rs.getBoolean("enabled") || !rs.getBoolean("email_verified")).roles("USER").build(), username.toLowerCase(java.util.Locale.ROOT))
                .stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    @DependsOnDatabaseInitialization
    JWKSource<SecurityContext> jwkSource(JdbcTemplate jdbc) throws Exception {
        var saved = jdbc.queryForList("SELECT private_jwk FROM auth.signing_key WHERE id = 'primary'", String.class);
        if (saved.isEmpty()) {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            var generated = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(UUID.randomUUID().toString()).build();
            jdbc.update("INSERT INTO auth.signing_key(id,private_jwk) VALUES ('primary',?) ON CONFLICT (id) DO NOTHING", generated.toJSONString());
        }
        var key = RSAKey.parse(jdbc.queryForObject("SELECT private_jwk FROM auth.signing_key WHERE id = 'primary'", String.class));
        return new ImmutableJWKSet<>(new JWKSet(key));
    }

    @Bean
    JwtEncoder jwtEncoder(JWKSource<SecurityContext> source) {
        return new NimbusJwtEncoder(source);
    }

    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> source,
                          @Value("${spring.security.oauth2.authorizationserver.issuer}") String issuer) throws Exception {
        var keys = source.get(new com.nimbusds.jose.jwk.JWKSelector(new com.nimbusds.jose.jwk.JWKMatcher.Builder().keyType(KeyType.RSA).build()), null);
        var decoder = NimbusJwtDecoder.withPublicKey(((RSAKey) keys.getFirst()).toRSAPublicKey()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

    @Bean
    @Order(-200)
    SecurityFilterChain authorizationServer(HttpSecurity http) throws Exception {
        var server = new org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer();
        var html = new org.springframework.security.web.util.matcher.MediaTypeRequestMatcher(org.springframework.http.MediaType.TEXT_HTML);
        html.setIgnoredMediaTypes(java.util.Set.of(org.springframework.http.MediaType.ALL));
        return http.securityMatcher(server.getEndpointsMatcher())
                .with(server, config -> config.oidc(Customizer.withDefaults()))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(resource -> resource.jwt(Customizer.withDefaults()))
                .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                        new org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint("/login"), html))
                .build();
    }

    @Bean
    @Order(100)
    SecurityFilterChain browserLogin(HttpSecurity http, UserDetailsService users, PasswordEncoder encoder, com.pbs.authservice.auth.MfaService mfa) throws Exception {
        return http.authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health", "/error", "/reset-password.html", "/verify-email.html").permitAll()
                        .anyRequest().authenticated())
                .authenticationManager(new ProviderManager(new com.pbs.authservice.auth.BrowserMfaProvider(users, encoder, mfa)))
                .formLogin(form -> form.loginPage("/login").permitAll().authenticationDetailsSource(com.pbs.authservice.auth.MfaWebDetails::new)).build();
    }

    @Bean
    @Order(-100)
    SecurityFilterChain authApi(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api/v1/auth/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/token", "/api/v1/auth/refresh", "/api/v1/auth/logout", "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password", "/api/v1/auth/verify-email", "/api/v1/auth/email-verification/request", "/api/v1/auth/mfa/verify").permitAll()
                        .anyRequest().authenticated())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
                .build();
    }

    @Bean
    ApplicationRunner demoUser(JdbcTemplate jdbc, PasswordEncoder encoder,
                               @Value("${auth.demo-enabled:true}") boolean enabled) {
        return args -> {
            if (enabled)
                jdbc.update("INSERT INTO auth.app_user(username,password_hash,enabled) VALUES (?,?,true) ON CONFLICT (username) DO NOTHING", "user", encoder.encode("password"));
        };
    }
}
