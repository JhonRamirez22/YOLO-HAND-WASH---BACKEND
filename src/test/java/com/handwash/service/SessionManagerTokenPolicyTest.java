package com.handwash.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.handwash.model.ProtocolType;

class SessionManagerTokenPolicyTest {
    @Test
    void allowsTokenLifetimeFromOneSecondThroughOneDay() {
        SessionManager manager = new SessionManager(null);

        ReflectionTestUtils.setField(manager, "accessTokenTtlMs", 1_000L);
        assertDoesNotThrow(manager::validarConfiguracionRuntime);

        ReflectionTestUtils.setField(manager, "accessTokenTtlMs", 86_400_000L);
        assertDoesNotThrow(manager::validarConfiguracionRuntime);
    }

    @Test
    void rejectsTokenLifetimeAboveOneDayOrBelowOneSecond() {
        SessionManager manager = new SessionManager(null);

        ReflectionTestUtils.setField(manager, "accessTokenTtlMs", 86_400_001L);
        assertThrows(IllegalArgumentException.class, manager::validarConfiguracionRuntime);

        ReflectionTestUtils.setField(manager, "accessTokenTtlMs", 999L);
        assertThrows(IllegalArgumentException.class, manager::validarConfiguracionRuntime);
    }

    @Test
    void rejectsInvalidSessionRuntimeLimitsBeforeServingRequests() {
        SessionManager manager = new SessionManager(null);

        ReflectionTestUtils.setField(manager, "maxSessions", 0);
        assertThrows(IllegalStateException.class, manager::validarConfiguracionRuntime);

        ReflectionTestUtils.setField(manager, "maxSessions", 4_097);
        assertThrows(IllegalStateException.class, manager::validarConfiguracionRuntime);

        ReflectionTestUtils.setField(manager, "maxSessions", 128);
        ReflectionTestUtils.setField(manager, "maxActiveSessions", 129);
        assertThrows(IllegalStateException.class, manager::validarConfiguracionRuntime);

        ReflectionTestUtils.setField(manager, "maxActiveSessions", 128);
        ReflectionTestUtils.setField(manager, "timeoutMinutes", 0L);
        assertThrows(IllegalStateException.class, manager::validarConfiguracionRuntime);

        ReflectionTestUtils.setField(manager, "timeoutMinutes", 5L);
        ReflectionTestUtils.setField(manager, "terminalRetentionMinutes", 0L);
        assertThrows(IllegalStateException.class, manager::validarConfiguracionRuntime);

        ReflectionTestUtils.setField(manager, "terminalRetentionMinutes", 30L);
        ReflectionTestUtils.setField(manager, "maxDetectionGapMs", 99L);
        assertThrows(IllegalStateException.class, manager::validarConfiguracionRuntime);

        ReflectionTestUtils.setField(manager, "maxDetectionGapMs", 1_500L);
        ReflectionTestUtils.setField(manager, "maxStepTransitionConfirmGapMs", 1_501L);
        assertThrows(IllegalStateException.class, manager::validarConfiguracionRuntime);

        ReflectionTestUtils.setField(manager, "maxStepTransitionConfirmGapMs", 650L);
        assertDoesNotThrow(manager::validarConfiguracionRuntime);
    }

    @Test
    void terminalSessionRetentionStillExpiresAfterBackwardWallClockCorrection() {
        SessionManager manager = new SessionManager(new com.handwash.agent.Receiver());
        ReflectionTestUtils.setField(manager, "terminalRetentionMinutes", 1L);
        String sessionId = manager.crearSesion(ProtocolType.DOMESTICO);
        var session = manager.getSesion(sessionId);
        session.expirar();

        ReflectionTestUtils.setField(session, "ultimaActividadTimestamp",
            System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1));
        ReflectionTestUtils.setField(session, "ultimaActividadMonotonicNanos",
            System.nanoTime() - TimeUnit.SECONDS.toNanos(61));

        manager.limpiarSesionesTerminadas();

        assertNull(manager.getSesion(sessionId),
            "la retención usa tiempo monotónico aunque el reloj de pared retroceda");
    }

    @Test
    void expiresOwnerTokenAgainstMonotonicTimeAtTheExactTtlBoundary() {
        SessionManager manager = new SessionManager(null);
        AtomicLong monotonicClock = new AtomicLong(10L);
        AtomicLong wallClock = new AtomicLong(1_000_000L);
        ReflectionTestUtils.setField(manager, "accessTokenTtlMs", 1_000L);
        ReflectionTestUtils.setField(manager, "monotonicNanos",
            (java.util.function.LongSupplier) monotonicClock::get);
        ReflectionTestUtils.setField(manager, "epochMillis",
            (java.util.function.LongSupplier) wallClock::get);
        String sessionId = manager.crearSesion(ProtocolType.DOMESTICO);
        String token = manager.getOwnerToken(sessionId);

        assertNotNull(token);
        assertTrue(manager.tieneAcceso(sessionId, token, true));
        long deadline = 10L + TimeUnit.MILLISECONDS.toNanos(1_000L);
        monotonicClock.set(deadline - 1L);
        assertTrue(manager.tieneAcceso(sessionId, token, true));

        wallClock.set(500_000L); // a backward clock correction cannot extend the token
        monotonicClock.incrementAndGet();
        assertFalse(manager.tieneAcceso(sessionId, token, true));
        assertNull(manager.getOwnerToken(sessionId));
    }

    @Test
    void loginIssuedDeviceTokenExpiresAtTheConfiguredMonotonicBoundary() {
        SessionManager manager = new SessionManager(null);
        AtomicLong monotonicClock = new AtomicLong(100L);
        AtomicLong wallClock = new AtomicLong(3_000_000L);
        ReflectionTestUtils.setField(manager, "accessTokenTtlMs", 1_000L);
        ReflectionTestUtils.setField(manager, "monotonicNanos",
            (java.util.function.LongSupplier) monotonicClock::get);
        ReflectionTestUtils.setField(manager, "epochMillis",
            (java.util.function.LongSupplier) wallClock::get);
        String sessionId = manager.crearSesion(ProtocolType.DOMESTICO);

        SessionManager.TokenIssue loginToken = manager.emitirTokenDispositivo(sessionId);
        assertNotNull(loginToken);
        assertEquals(3_001_000L, loginToken.expiresAtEpochMs());
        assertTrue(manager.tieneAcceso(sessionId, loginToken.value(), false));

        monotonicClock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1_000L));
        assertFalse(manager.tieneAcceso(sessionId, loginToken.value(), false));
    }

    @Test
    void authenticatedAccessCarriesTheMonotonicDeadlineForLongLivedConsumers() {
        SessionManager manager = new SessionManager(null);
        AtomicLong monotonicClock = new AtomicLong(50L);
        AtomicLong wallClock = new AtomicLong(2_000_000L);
        ReflectionTestUtils.setField(manager, "accessTokenTtlMs", 1_000L);
        ReflectionTestUtils.setField(manager, "monotonicNanos",
            (java.util.function.LongSupplier) monotonicClock::get);
        ReflectionTestUtils.setField(manager, "epochMillis",
            (java.util.function.LongSupplier) wallClock::get);
        String sessionId = manager.crearSesion(ProtocolType.DOMESTICO);
        String token = manager.getOwnerToken(sessionId);

        SessionManager.AccessInfo access = manager.autenticar(sessionId, token);

        assertNotNull(access);
        assertEquals(2_001_000L, access.expiresAtEpochMs());
        assertEquals(50L + TimeUnit.MILLISECONDS.toNanos(1_000L),
            access.expiresAtMonotonicNanos());
        assertNotNull(access.credentialRevision());
    }

    @Test
    void deviceTokenRotationChangesCredentialRevisionWithoutRevokingOwner() {
        SessionManager manager = new SessionManager(null);
        String sessionId = manager.crearSesion(ProtocolType.DOMESTICO);
        String ownerToken = manager.getOwnerToken(sessionId);
        String oldDeviceToken = manager.getDeviceToken(sessionId);
        SessionManager.AccessInfo ownerAccess = manager.autenticar(sessionId, ownerToken);
        SessionManager.AccessInfo oldDeviceAccess = manager.autenticar(sessionId, oldDeviceToken);

        SessionManager.TokenIssue replacement = manager.emitirTokenDispositivo(sessionId);

        SessionManager.AccessInfo newDeviceAccess = manager.autenticar(sessionId, replacement.value());
        assertNotNull(newDeviceAccess);
        assertTrue(manager.credencialVigente(sessionId, ownerAccess));
        assertFalse(manager.credencialVigente(sessionId, oldDeviceAccess));
        assertTrue(manager.credencialVigente(sessionId, newDeviceAccess));
        assertNotEquals(oldDeviceAccess.credentialRevision(), newDeviceAccess.credentialRevision());
    }

    @Test
    void dashboardCredentialIsReadOnlyAndRotatesIndependentlyFromProducerCredentials() {
        SessionManager manager = new SessionManager(null);
        String sessionId = manager.crearSesion(ProtocolType.DOMESTICO, true);
        String ownerToken = manager.getOwnerToken(sessionId);
        String deviceToken = manager.getDeviceToken(sessionId);

        SessionManager.TokenIssue firstViewer = manager.emitirTokenDashboard(sessionId);
        SessionManager.AccessInfo firstViewerAccess = manager.autenticar(sessionId, firstViewer.value());
        assertEquals(SessionManager.AccessRole.VIEWER, firstViewerAccess.role());
        assertTrue(manager.tieneAccesoLectura(sessionId, firstViewer.value()));
        assertFalse(manager.tieneAcceso(sessionId, firstViewer.value(), false));
        assertFalse(manager.tieneAcceso(sessionId, firstViewer.value(), true));
        assertTrue(manager.credencialVigente(sessionId, firstViewerAccess));

        SessionManager.TokenIssue secondViewer = manager.emitirTokenDashboard(sessionId);
        SessionManager.AccessInfo secondViewerAccess = manager.autenticar(sessionId, secondViewer.value());
        assertFalse(manager.tieneAccesoLectura(sessionId, firstViewer.value()));
        assertFalse(manager.credencialVigente(sessionId, firstViewerAccess));
        assertTrue(manager.tieneAccesoLectura(sessionId, secondViewer.value()));
        assertTrue(manager.credencialVigente(sessionId, secondViewerAccess));

        assertTrue(manager.tieneAcceso(sessionId, ownerToken, true));
        assertTrue(manager.tieneAcceso(sessionId, deviceToken, false));
    }
}
