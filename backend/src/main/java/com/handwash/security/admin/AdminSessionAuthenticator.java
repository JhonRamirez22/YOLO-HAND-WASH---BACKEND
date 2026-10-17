package com.handwash.security.admin;

import com.handwash.security.CredentialAuthenticator;
import com.handwash.security.SyntheticTokens;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** {@code Authorization: Bearer hwa_…}: a local ADMIN session. Any other bearer still goes to the (rejecting) JWT decoder. */
@Component
@Profile("onprem")
@Order(0)
public class AdminSessionAuthenticator implements CredentialAuthenticator {
    private static final String PREFIX = "Bearer " + AdminAuthService.TOKEN_PREFIX;
    private final AdminAuthService auth;

    public AdminSessionAuthenticator(AdminAuthService auth) { this.auth = auth; }

    @Override
    public Optional<Outcome> authenticate(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(PREFIX)) return Optional.empty();
        return Optional.of(auth.authenticate(header.substring("Bearer ".length()).trim())
            .map(principal -> Outcome.accepted(SyntheticTokens.admin(principal.subject())))
            .orElseGet(Outcome::rejected));
    }
}
