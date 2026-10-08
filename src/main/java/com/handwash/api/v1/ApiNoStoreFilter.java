package com.handwash.api.v1;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Prevents browsers and intermediaries from caching credentials or session data returned by the API. */
@Component
public final class ApiNoStoreFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            String requestUri = request.getRequestURI();
            String contextPath = request.getContextPath();
            path = requestUri.substring(Math.min(contextPath.length(), requestUri.length()));
        }
        if ("/api".equals(path) || path.startsWith("/api/")) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Pragma", "no-cache");
            response.setDateHeader("Expires", 0L);
        }
        filterChain.doFilter(request, response);
    }
}
