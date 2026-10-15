package com.handwash.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Chain of responsibility: API-key schemes are tried before Cognito's bearer filter; anything else passes through. */
public final class CredentialAuthenticationFilter extends OncePerRequestFilter {
    private final List<CredentialAuthenticator> authenticators;

    public CredentialAuthenticationFilter(List<CredentialAuthenticator> authenticators) {
        this.authenticators = List.copyOf(authenticators);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        for (CredentialAuthenticator authenticator : authenticators) {
            Optional<CredentialAuthenticator.Outcome> outcome = authenticator.authenticate(request);
            if (outcome.isEmpty()) continue;
            if (outcome.get().isAccepted()) {
                SecurityContextHolder.getContext().setAuthentication(outcome.get().authentication());
                chain.doFilter(request, response);
                return;
            }
            SecurityContextHolder.clearContext();
            response.setStatus(outcome.get().locked() ? 429 : 401);
            response.setContentType("application/json");
            response.getWriter().write(outcome.get().locked() ? "{\"error\":\"LIMITE_INTENTOS_KEY\"}"
                : "{\"error\":\"CREDENCIAL_NO_VALIDA\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
