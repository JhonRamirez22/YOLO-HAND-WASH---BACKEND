package com.handwash.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;

import java.util.Optional;

/**
 * Strategy for one kind of non-Cognito credential. {@link CredentialAuthenticationFilter} asks each
 * authenticator in order (chain of responsibility); the first one that recognises its scheme decides.
 */
public interface CredentialAuthenticator {
    /** Empty when the request does not carry this authenticator's scheme. */
    Optional<Outcome> authenticate(HttpServletRequest request);

    record Outcome(Authentication authentication, boolean locked) {
        public static Outcome accepted(Authentication authentication) { return new Outcome(authentication, false); }
        public static Outcome rejected() { return new Outcome(null, false); }
        public static Outcome lockedOut() { return new Outcome(null, true); }
        public boolean isAccepted() { return authentication != null; }
    }
}
