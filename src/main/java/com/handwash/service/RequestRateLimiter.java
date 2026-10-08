package com.handwash.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Límite local para evitar abuso accidental y fuerza bruta de vinculación. */
@Service
public class RequestRateLimiter {
    private static final long MAX_WINDOW_MS = TimeUnit.DAYS.toMillis(1);
    private static final int MAX_REQUESTS_PER_WINDOW = 10_000;
    private static final int MAX_CLIENTS = 100_000;

    private final LongSupplier monotonicNanos;
    private final Map<String, Deque<Long>> ventanas = new HashMap<>();

    @Value("${handwash.security.rate-limit-window-ms:60000}")
    private long windowMs = 60_000L;
    @Value("${handwash.security.session-create-per-window:20}")
    private int createLimit = 20;
    @Value("${handwash.security.session-pair-per-window:10}")
    private int pairLimit = 10;
    @Value("${handwash.security.rate-limit-max-clients:10000}")
    private int maxClients = 10_000;

    public RequestRateLimiter() { this(System::nanoTime); }
    RequestRateLimiter(LongSupplier monotonicNanos) { this.monotonicNanos = monotonicNanos; }

    @PostConstruct
    void validateConfiguration() {
        if (windowMs < 1L || windowMs > MAX_WINDOW_MS) {
            throw new IllegalStateException(
                "handwash.security.rate-limit-window-ms debe estar entre 1 y " + MAX_WINDOW_MS);
        }
        if (createLimit < 1 || createLimit > MAX_REQUESTS_PER_WINDOW) {
            throw new IllegalStateException(
                "handwash.security.session-create-per-window debe estar entre 1 y "
                    + MAX_REQUESTS_PER_WINDOW);
        }
        if (pairLimit < 1 || pairLimit > MAX_REQUESTS_PER_WINDOW) {
            throw new IllegalStateException(
                "handwash.security.session-pair-per-window debe estar entre 1 y "
                    + MAX_REQUESTS_PER_WINDOW);
        }
        if (maxClients < 1 || maxClients > MAX_CLIENTS) {
            throw new IllegalStateException(
                "handwash.security.rate-limit-max-clients debe estar entre 1 y " + MAX_CLIENTS);
        }
    }

    public boolean allowSessionCreation(String clientKey) {
        return allow("create:" + normalize(clientKey), createLimit);
    }

    public boolean allowPairing(String clientKey) {
        return allow("pair:" + normalize(clientKey), pairLimit);
    }

    private boolean allow(String key, int limit) {
        long now = monotonicNanos.getAsLong();
        long allowedWindowNanos = windowNanos();
        synchronized (ventanas) {
            if (!ventanas.containsKey(key) && ventanas.size() >= maxClients) {
                limpiarVentanasExpiradas(now, allowedWindowNanos);
                if (ventanas.size() >= maxClients) return false;
            }
            Deque<Long> requests = ventanas.computeIfAbsent(key, ignored -> new ArrayDeque<>());
            discardExpired(requests, now, allowedWindowNanos);
            if (requests.size() >= limit) return false;
            requests.addLast(now);
            return true;
        }
    }

    private long windowNanos() {
        return TimeUnit.MILLISECONDS.toNanos(windowMs);
    }

    private void discardExpired(Deque<Long> requests, long now, long allowedWindowNanos) {
        // nanoTime is an elapsed-time source, not a calendar. Subtraction also
        // remains correct across its signed-long wrap for intervals < 292 years.
        while (!requests.isEmpty() && now - requests.peekFirst() >= allowedWindowNanos) {
            requests.removeFirst();
        }
    }

    private String normalize(String key) {
        return key == null || key.isBlank() ? "unknown" : key.trim();
    }

    @Scheduled(fixedDelayString = "${handwash.security.rate-limit-cleanup-ms:60000}")
    public void limpiarVentanas() {
        limpiarVentanasExpiradas(monotonicNanos.getAsLong(), windowNanos());
    }

    private void limpiarVentanasExpiradas(long now, long allowedWindowNanos) {
        synchronized (ventanas) {
            Iterator<Map.Entry<String, Deque<Long>>> iterator = ventanas.entrySet().iterator();
            while (iterator.hasNext()) {
                Deque<Long> requests = iterator.next().getValue();
                discardExpired(requests, now, allowedWindowNanos);
                if (requests.isEmpty()) iterator.remove();
            }
        }
    }
}
