package com.handwash.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Resolves the client address once per request, before anything that rate-limits by it. */
public final class ClientAddressFilter extends OncePerRequestFilter {
    private final int trustedHops;

    public ClientAddressFilter(int trustedHops) { this.trustedHops = trustedHops; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        request.setAttribute(ClientAddress.ATTRIBUTE,
            ClientAddress.resolve(request.getHeader("X-Forwarded-For"), request.getRemoteAddr(), trustedHops));
        chain.doFilter(request, response);
    }
}
