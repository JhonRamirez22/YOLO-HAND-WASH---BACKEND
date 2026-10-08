package com.handwash.service;

import com.handwash.agent.Receiver;
import com.handwash.model.ProtocolType;
import com.handwash.security.SessionAuthenticationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SessionManagerConcurrencyTest {
    @Test
    void activeSessionFallsBackWhenLatestSessionTerminates() {
        SessionManager manager = new SessionManager(new Receiver());
        String first = manager.crearSesion(ProtocolType.DOMESTICO);
        String latest = manager.crearSesion(ProtocolType.CLINICO_QUIRURGICO);
        assertNull(manager.getSesionActiva(), "Dos sesiones activas no se deben elegir arbitrariamente");
        manager.getSesion(latest).expirar();

        assertEquals(first, manager.getSesionActiva().getSessionId());
        manager.eliminarSesion(latest);
        assertEquals(first, manager.getSesionActiva().getSessionId());
    }

    @Test
    void activeSessionLimitAllowsOnlyOneLiveWashButRetainsTerminalHistory() {
        SessionManager manager = new SessionManager(new Receiver());
        ReflectionTestUtils.setField(manager, "maxActiveSessions", 1);
        String first = manager.crearSesion(ProtocolType.DOMESTICO);

        assertThrows(SessionCapacityException.class,
            () -> manager.crearSesion(ProtocolType.DOMESTICO));

        manager.getSesion(first).expirar();
        String next = manager.crearSesion(ProtocolType.DOMESTICO);
        assertNotEquals(first, next);
        assertEquals(2, manager.getSesiones().size(),
            "la sesión terminal permanece disponible durante su retención configurada");
        assertEquals(next, manager.getSesionActiva().getSessionId());

        manager.eliminarSesion(first);
        manager.eliminarSesion(next);
    }

    @Test
    void concurrentCreationCannotExceedActiveStationSessionLimit() throws Exception {
        SessionManager manager = new SessionManager(new Receiver());
        ReflectionTestUtils.setField(manager, "maxActiveSessions", 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        Runnable create = () -> {
            try {
                start.await();
                manager.crearSesion(ProtocolType.DOMESTICO);
                created.incrementAndGet();
            } catch (SessionCapacityException expected) {
                limited.incrementAndGet();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        Thread first = new Thread(create);
        Thread second = new Thread(create);
        first.start();
        second.start();
        start.countDown();
        first.join(TimeUnit.SECONDS.toMillis(3));
        second.join(TimeUnit.SECONDS.toMillis(3));

        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertEquals(1, created.get());
        assertEquals(1, limited.get());
        assertEquals(1, manager.getSesiones().size());
    }

    @Test
    void pairingCodesAreUniqueAndOnlyResolveActiveSessions() {
        SessionManager manager = new SessionManager(new Receiver());
        String first = manager.crearSesion(ProtocolType.DOMESTICO);
        String second = manager.crearSesion(ProtocolType.DOMESTICO);
        String firstCode = manager.getCodigoEmparejamiento(first);
        String secondCode = manager.getCodigoEmparejamiento(second);

        assertNotNull(firstCode);
        assertNotEquals(firstCode, secondCode);
        assertEquals(first, manager.getSesionPorCodigo(firstCode.toLowerCase()).getSessionId());
        assertEquals(second, manager.getSesionPorCodigo(secondCode.replace("-", "")).getSessionId());
        assertNull(manager.getSesionPorCodigo("INVALIDO"));

        manager.getSesion(first).expirar();
        assertNull(manager.getSesionPorCodigo(firstCode));
        manager.eliminarSesion(second);
        assertNull(manager.getSesionPorCodigo(secondCode));
    }

    @Test
    void ownerAndDeviceTokensHaveDifferentPrivilegesAndAreRevokedOnDeletion() {
        SessionManager manager = new SessionManager(new Receiver());
        String id = manager.crearSesion(ProtocolType.DOMESTICO);
        String owner = manager.getOwnerToken(id);
        String device = manager.getDeviceToken(id);

        assertNotNull(owner);
        assertNotNull(device);
        assertNotEquals(owner, device);
        assertFalse(manager.tieneAcceso(id, null, false));
        assertFalse(manager.tieneAcceso(id, "invalid", false));
        assertTrue(manager.tieneAcceso(id, owner, true));
        assertTrue(manager.tieneAcceso(id, owner, false));
        assertTrue(manager.tieneAcceso(id, device, false));
        assertFalse(manager.tieneAcceso(id, device, true));

        manager.eliminarSesion(id);
        assertFalse(manager.tieneAcceso(id, owner, false));
        assertFalse(manager.tieneAcceso(id, device, false));
    }

    @Test
    void revokedDeviceTokenCannotRegisterEpochAfterV2Relogin() {
        SessionManager manager = new SessionManager(new Receiver());
        String id = manager.crearSesion(ProtocolType.DOMESTICO);
        String pairingCode = manager.getCodigoEmparejamiento(id);
        String oldDeviceToken = manager.getDeviceToken(id);
        SessionAuthenticationService authentication = new SessionAuthenticationService(manager);

        SessionAuthenticationService.DeviceLogin login = authentication.loginWithPairingCode(pairingCode, 2);
        assertTrue(login.successful());
        assertFalse(oldDeviceToken.equals(login.token().value()));

        SessionManager.ProducerEpochResult stale = manager.registrarProducerEpoch(id, oldDeviceToken);
        assertEquals(SessionManager.ProducerEpochOutcome.UNAUTHORIZED, stale.outcome());
        assertNull(stale.registration());

        SessionManager.ProducerEpochResult current =
            manager.registrarProducerEpoch(id, login.token().value());
        assertEquals(SessionManager.ProducerEpochOutcome.REGISTERED, current.outcome());
        assertEquals(2, current.registration().protocolVersion());
    }

    @Test
    void concurrentCreationCannotExceedConfiguredSessionLimit() throws Exception {
        SessionManager manager = new SessionManager(new Receiver());
        ReflectionTestUtils.setField(manager, "maxSessions", 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        Runnable create = () -> {
            try {
                start.await();
                manager.crearSesion(ProtocolType.DOMESTICO);
                created.incrementAndGet();
            } catch (SessionCapacityException expected) {
                limited.incrementAndGet();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        Thread first = new Thread(create);
        Thread second = new Thread(create);
        first.start();
        second.start();
        start.countDown();
        first.join(TimeUnit.SECONDS.toMillis(3));
        second.join(TimeUnit.SECONDS.toMillis(3));

        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertEquals(1, created.get());
        assertEquals(1, limited.get());
        assertEquals(1, manager.getSesiones().size());
    }

    @Test
    void removedSessionCannotReceiveFurtherDetections() {
        Receiver receptor = new Receiver();
        SessionManager manager = new SessionManager(receptor);
        String sessionId = manager.crearSesion(ProtocolType.DOMESTICO);
        manager.eliminarSesion(sessionId);

        assertNull(manager.procesarDeteccion(sessionId, "Paso1_Palmas", 0.9f));
        assertNull(manager.getSesion(sessionId));
    }

    @Test
    void serializesFullObserverPipelineForTheSameSession() throws Exception {
        Receiver receptor = new Receiver();
        SessionManager manager = new SessionManager(receptor);
        String sessionId = manager.crearSesion(ProtocolType.DOMESTICO);
        AtomicInteger processing = new AtomicInteger();
        AtomicInteger maximumConcurrent = new AtomicInteger();
        receptor.addObserver(event -> {
            int current = processing.incrementAndGet();
            maximumConcurrent.accumulateAndGet(current, Math::max);
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                processing.decrementAndGet();
            }
        });

        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    manager.procesarDeteccion(sessionId, "Paso1_Palmas", 0.9f);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : threads) thread.join(TimeUnit.SECONDS.toMillis(3));

        assertTrue(threads.stream().noneMatch(Thread::isAlive));
        assertEquals(1, maximumConcurrent.get());
    }
}
