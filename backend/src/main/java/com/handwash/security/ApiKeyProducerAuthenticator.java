package com.handwash.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** {@code Authorization: ApiKey hwk_…} from the YOLO PC: a key with the PRODUCER scope becomes the station's producer. */
@Component
@Profile("cloud")
@Order(1)
public class ApiKeyProducerAuthenticator implements CredentialAuthenticator {
    static final String SCHEME = "ApiKey ";
    private final ApiKeyService keys;

    public ApiKeyProducerAuthenticator(ApiKeyService keys) { this.keys = keys; }

    @Override
    public Optional<Outcome> authenticate(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(SCHEME)) return Optional.empty();
        ApiKeyService.Verification verification = keys.verify(header.substring(SCHEME.length()).trim(), com.handwash.security.ClientAddress.of(request));
        if (verification.locked()) return Optional.of(Outcome.lockedOut());
        if (!verification.accepted() || !verification.scopes().contains(ApiKeyScope.PRODUCER)) {
            return Optional.of(Outcome.rejected());
        }
        return Optional.of(Outcome.accepted(SyntheticTokens.producer(verification.keyId())));
    }
}
