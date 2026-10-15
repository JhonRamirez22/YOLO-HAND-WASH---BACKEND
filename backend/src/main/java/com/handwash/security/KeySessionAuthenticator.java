package com.handwash.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** {@code Authorization: KeySession hws_…} from the operator console, checked against the key on every request. */
@Component
@Profile("cloud")
@Order(2)
public class KeySessionAuthenticator implements CredentialAuthenticator {
    static final String SCHEME = "KeySession ";
    private final ApiKeyService keys;

    public KeySessionAuthenticator(ApiKeyService keys) { this.keys = keys; }

    @Override
    public Optional<Outcome> authenticate(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(SCHEME)) return Optional.empty();
        ApiKeyService.Verification verification = keys.authenticateSession(header.substring(SCHEME.length()).trim());
        if (!verification.accepted()) return Optional.of(Outcome.rejected());
        return Optional.of(Outcome.accepted(SyntheticTokens.console(verification.keyId())));
    }
}
