package com.handwash.agent;

import com.handwash.model.HandwashingSession;
import com.handwash.model.OmsAction;
import com.handwash.model.DetectionEvent;
import com.handwash.model.SoapEvidence;
import com.handwash.model.SoapEvidenceStatus;
import com.handwash.model.HandwashingStatusResponse;
import com.handwash.model.HandwashingSessionState;
import com.handwash.model.SoapRegion;
import com.handwash.model.ProtocolType;
import com.handwash.model.MovementEvidence;
import com.handwash.intention.HandwashingIntentChain;
import com.handwash.service.SessionManager;
import com.handwash.service.HandwashMetrics;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.SendHandler;
import jakarta.websocket.SendResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.adapter.NativeWebSocketSession;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class NotifierTest {
    @Test
    void sendTimeoutUsesElapsedTimeWhenMonotonicOriginIsNegative() {
        long startedAt = -TimeUnit.SECONDS.toNanos(10);
        long timeout = TimeUnit.SECONDS.toNanos(3);

        assertFalse(Notifier.sendTimedOut(true, startedAt,
            startedAt + TimeUnit.SECONDS.toNanos(2), timeout));
        assertTrue(Notifier.sendTimedOut(true, startedAt,
            startedAt + TimeUnit.SECONDS.toNanos(3), timeout));
        assertFalse(Notifier.sendTimedOut(false, startedAt,
            startedAt + TimeUnit.SECONDS.toNanos(10), timeout));
    }

    @Test
    void authenticatedSocketIsClosedOnceItsAccessTokenExpires() throws Exception {
        SessionManager manager = new SessionManager(new Receiver());
        String id = manager.crearSesion(ProtocolType.DOMESTICO);
        Notifier notifier = new Notifier(manager);
        AtomicBoolean open = new AtomicBoolean(true);
        AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();
        WebSocketSession client = (WebSocketSession) Proxy.newProxyInstance(
            WebSocketSession.class.getClassLoader(), new Class<?>[]{WebSocketSession.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "isOpen" -> open.get();
                case "getId" -> "expiring-client";
                case "close" -> {
                    closeStatus.set((CloseStatus) args[0]);
                    open.set(false);
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });

        try {
            assertTrue(notifier.registrarSesion(id, client, System.currentTimeMillis() - 1));
            ReflectionTestUtils.invokeMethod(notifier, "dispatchPendingOutbound");
            assertFalse(open.get());
            assertEquals(CloseStatus.POLICY_VIOLATION.getCode(), closeStatus.get().getCode());
            assertEquals("Credencial expirada", closeStatus.get().getReason());
        } finally {
            notifier.cerrarEnviadores();
        }
    }

    @Test
    void authenticatedSocketExpiresMonotonicallyEvenWhenWallClockExpiryIsStillInFuture() throws Exception {
        SessionManager manager = new SessionManager(new Receiver());
        String id = manager.crearSesion(ProtocolType.DOMESTICO);
        Notifier notifier = new Notifier(manager);
        AtomicBoolean open = new AtomicBoolean(true);
        AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();
        WebSocketSession client = (WebSocketSession) Proxy.newProxyInstance(
            WebSocketSession.class.getClassLoader(), new Class<?>[]{WebSocketSession.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "isOpen" -> open.get();
                case "getId" -> "monotonic-expiring-client";
                case "close" -> {
                    closeStatus.set((CloseStatus) args[0]);
                    open.set(false);
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });

        try {
            assertTrue(notifier.registrarSesion(id, client,
                System.currentTimeMillis() + 60_000L, System.nanoTime() - 1L));
            ReflectionTestUtils.invokeMethod(notifier, "dispatchPendingOutbound");
            assertFalse(open.get(), "monotonic expiry must close the socket before a future wall deadline");
            assertEquals(CloseStatus.POLICY_VIOLATION.getCode(), closeStatus.get().getCode());
            assertEquals("Credencial expirada", closeStatus.get().getReason());
        } finally {
            notifier.cerrarEnviadores();
        }
    }

    @Test
    void connectedSocketClosesWhenItsDeviceTokenIsRotated() throws Exception {
        SessionManager manager = new SessionManager(new Receiver());
        String id = manager.crearSesion(ProtocolType.DOMESTICO);
        String oldToken = manager.getDeviceToken(id);
        SessionManager.AccessInfo oldAccess = manager.autenticar(id, oldToken);
        Notifier notifier = new Notifier(manager);
        AtomicBoolean open = new AtomicBoolean(true);
        AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();
        WebSocketSession client = (WebSocketSession) Proxy.newProxyInstance(
            WebSocketSession.class.getClassLoader(), new Class<?>[]{WebSocketSession.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "isOpen" -> open.get();
                case "getId" -> "revoked-device-client";
                case "close" -> {
                    closeStatus.set((CloseStatus) args[0]);
                    open.set(false);
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });

        try {
            assertNotNull(oldAccess);
            assertTrue(manager.credencialVigente(id, oldAccess));
            assertTrue(notifier.registrarSesion(id, client, oldAccess));
            assertNotNull(manager.emitirTokenDispositivo(id));
            assertFalse(manager.credencialVigente(id, oldAccess));

            ReflectionTestUtils.invokeMethod(notifier, "dispatchPendingOutbound");

            assertFalse(open.get(), "a socket must not retain a rotated DEVICE capability");
            assertEquals(CloseStatus.POLICY_VIOLATION.getCode(), closeStatus.get().getCode());
            assertEquals("Credencial revocada", closeStatus.get().getReason());
        } finally {
            notifier.cerrarEnviadores();
        }
    }

    @Test
    void asynchronousSendFailureRequestsSocketCloseAndUnregistersClient() throws Exception {
        SessionManager manager = new SessionManager(new Receiver());
        String id = manager.crearSesion(ProtocolType.DOMESTICO);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Notifier notifier = new Notifier(manager, new HandwashMetrics(registry));
        AtomicBoolean nativeOpen = new AtomicBoolean(true);
        AtomicInteger closeCalls = new AtomicInteger();
        CountDownLatch closed = new CountDownLatch(1);

        RemoteEndpoint.Async asyncRemote = (RemoteEndpoint.Async) Proxy.newProxyInstance(
            RemoteEndpoint.Async.class.getClassLoader(), new Class<?>[]{RemoteEndpoint.Async.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "setSendTimeout" -> null;
                case "sendText" -> {
                    ((SendHandler) args[1]).onResult(new SendResult(new IOException("send timeout")));
                    yield null;
                }
                case "toString" -> "failed-async-remote";
                default -> throw new UnsupportedOperationException(method.getName());
            });
        jakarta.websocket.Session nativeSession = (jakarta.websocket.Session) Proxy.newProxyInstance(
            jakarta.websocket.Session.class.getClassLoader(), new Class<?>[]{jakarta.websocket.Session.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "isOpen" -> nativeOpen.get();
                case "getAsyncRemote" -> asyncRemote;
                case "close" -> {
                    nativeOpen.set(false);
                    yield null;
                }
                case "getId" -> "native-failing-session";
                case "toString" -> "native-failing-session";
                default -> throw new UnsupportedOperationException(method.getName());
            });
        NativeWebSocketSession client = (NativeWebSocketSession) Proxy.newProxyInstance(
            NativeWebSocketSession.class.getClassLoader(), new Class<?>[]{NativeWebSocketSession.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "isOpen" -> nativeOpen.get();
                case "getNativeSession" -> nativeSession;
                case "getId" -> "native-failing-client";
                case "sendMessage" -> throw new AssertionError("native transport should use async send");
                case "close" -> {
                    closeCalls.incrementAndGet();
                    nativeOpen.set(false);
                    closed.countDown();
                    yield null;
                }
                case "toString" -> "native-failing-client";
                default -> throw new UnsupportedOperationException(method.getName());
            });

        try {
            assertTrue(notifier.registrarSesion(id, client));
            notifier.enviarEstado(id, state(id, "PASO_1_PALMAS"));

            assertTrue(closed.await(2, TimeUnit.SECONDS),
                "an async send failure must invoke the native WebSocket close, not just forget its mailbox");
            assertFalse(nativeOpen.get());
            assertEquals(1, closeCalls.get());
            assertFalse(((Map<WebSocketSession, ?>) ReflectionTestUtils.getField(notifier, "outboundClients"))
                .containsKey(client));
            assertEquals(1.0, registry.counter("handwash.websocket.send.failures",
                "cause", "async_callback").count());
        } finally {
            notifier.cerrarEnviadores();
        }
    }

    @Test
    void disconnectStopsTimerWhenAnInFlightWriteNeverReturns() throws Exception {
        SessionManager manager = new SessionManager(new Receiver());
        String id = manager.crearSesion(ProtocolType.DOMESTICO);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Notifier notifier = new Notifier(manager, new HandwashMetrics(registry));
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        WebSocketSession client = messageClient(id, ignored -> {
            sendStarted.countDown();
            try {
                if (!releaseSend.await(3, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("blocked send test timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("blocked send interrupted", interrupted);
            }
        }, () -> {});

        try {
            assertTrue(notifier.registrarSesion(id, client));
            notifier.enviarEstado(id, state(id, "PASO_1_PALMAS"));
            assertTrue(sendStarted.await(2, TimeUnit.SECONDS));
            assertEquals(0L, registry.timer("handwash.websocket.send").count());

            notifier.eliminarSesion(id, client);

            assertEquals(1L, registry.timer("handwash.websocket.send").count(),
                "disconnect must stop the in-flight timer even if the sender never calls back");
        } finally {
            releaseSend.countDown();
            notifier.cerrarEnviadores();
        }
    }

    @Test
    void rejectedFrictionCandidatePublishesReasonAndConfidenceOverWebSocket() throws Exception {
        SessionManager manager = new SessionManager(new Receiver());
        String id = manager.crearSesion(ProtocolType.DOMESTICO);
        HandwashingSession session = manager.getSesion(id);
        DetectionEvent candidate = new DetectionEvent(
            id, "Paso1_Palmas", 0.70f, Instant.now().toString());
        candidate.setServerReceivedAtMonotonicMs(1000L);
        candidate.setEvidenciaMovimiento(new MovementEvidence(1L, 2, 0.2, true, 100L));
        assertFalse(session.evaluarIntencion(candidate,
            new HandwashingIntentChain(1200, 650, 4, 1500, 0.75)));

        Notifier notifier = new Notifier(manager);
        List<String> sentMessages = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch sent = new CountDownLatch(1);
        WebSocketSession client = (WebSocketSession) Proxy.newProxyInstance(
            WebSocketSession.class.getClassLoader(), new Class<?>[]{WebSocketSession.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "isOpen" -> true;
                case "getId" -> "diagnostic-client";
                case "sendMessage" -> {
                    sentMessages.add(((TextMessage) args[0]).getPayload());
                    sent.countDown();
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
        notifier.registrarSesion(id, client);
        notifier.onDeteccion(candidate);
        notifier.vaciarEstadosPendientes();

        assertTrue(sent.await(2, TimeUnit.SECONDS));
        assertEquals(1, sentMessages.size());
        String payload = sentMessages.get(0);
        assertTrue(payload.contains(
            "\"motivoIntencion\":\"La confianza no alcanzó el umbral; se reinician los votos de inicio\""));
        assertTrue(payload.contains("\"claseCandidata\":\"PASO_1_PALMAS\""));
        assertTrue(payload.contains("\"confianzaCandidata\":0.7"));
        assertTrue(payload.contains("\"umbralConfirmacionIntencionMs\":1200"));
        notifier.cerrarEnviadores();
    }

    @Test
    void expiredSessionReportsIncompleteAndMissingSteps() {
        SessionManager manager = new SessionManager(new Receiver());
        HandwashingSession sesion = new HandwashingSession("expired", ProtocolType.DOMESTICO);
        Notifier notificador = new Notifier(manager);

        sesion.expirar();
        Map<String, Object> summary = notificador.crearResumen("expired", sesion);

        assertEquals("INCOMPLETO", summary.get("resultado"));
        assertEquals(false, summary.get("aprobado"));
        assertEquals(7, ((List<?>) summary.get("pasosFaltantes")).size());
        assertEquals("EXPIRADA", summary.get("estadoSesion"));
    }

    @Test
    void omsSummaryUsesCanonicalCodesForTimesAndMissingSteps() {
        SessionManager manager = new SessionManager(new Receiver());
        HandwashingSession session = new HandwashingSession("oms-summary-wire-codes", ProtocolType.DOMESTICO);
        session.procesarAccionOms(OmsAction.MOJAR_MANOS, Map.of(), 10_000L, 0.9f);
        for (int observation = 1; observation <= 7; observation++) {
            session.procesarAccionOms(OmsAction.MOJAR_MANOS, Map.of(),
                10_000L + observation * 800L, 0.9f);
        }
        Notifier notifier = new Notifier(manager);
        Map<String, Object> summary = notifier.crearResumen("oms-summary-wire-codes", session);

        assertTrue(((List<?>) summary.get("pasosFaltantes"))
            .contains(OmsAction.MOJAR_MANOS.getClaseModelo()));
        assertEquals(5L, ((Map<?, ?>) summary.get("tiempoPorPaso"))
            .get(OmsAction.MOJAR_MANOS.getClaseModelo()));
        notifier.cerrarEnviadores();
    }

    @Test
    void completedOmsSequencePublishesOnlyOneFinalSummary() throws Exception {
        SessionManager manager = new SessionManager(new Receiver());
        String id = manager.crearSesion(ProtocolType.DOMESTICO);
        HandwashingSession session = manager.getSesion(id);
        long timestamp = 10_000L;
        for (OmsAction action : OmsAction.SECUENCIA) {
            Map<String, SoapEvidence> evidence = new LinkedHashMap<>();
            for (SoapRegion region : SoapRegion.values()) {
                if (region.getAccionVerificacion() == action) {
                    evidence.put(region.name(), new SoapEvidence(SoapEvidenceStatus.ESPUMA_VISIBLE, 0.95f));
                }
            }
            for (int observation = 0; observation < 8; observation++) {
                session.procesarAccionOms(action, evidence, timestamp, 0.95f);
                timestamp += 800L;
            }
        }
        assertEquals("COMPLETADA", session.getEstadoSesion().name());

        Notifier notifier = new Notifier(manager);
        List<String> sentMessages = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch sent = new CountDownLatch(2);
        WebSocketSession client = (WebSocketSession) Proxy.newProxyInstance(
            WebSocketSession.class.getClassLoader(), new Class<?>[]{WebSocketSession.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "isOpen" -> true;
                case "getId" -> "recording-client";
                case "sendMessage" -> {
                    sentMessages.add(((TextMessage) args[0]).getPayload());
                    sent.countDown();
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
        notifier.registrarSesion(id, client);
        DetectionEvent finalEvent = new DetectionEvent(
            id, OmsAction.CERRAR_GRIFO_CON_TOALLA.getClaseModelo(), 0.95f, Instant.now().toString());

        notifier.onDeteccion(finalEvent);
        notifier.onDeteccion(finalEvent);
        notifier.vaciarEstadosPendientes();

        assertTrue(sent.await(2, TimeUnit.SECONDS));
        assertEquals(2, sentMessages.size()); // estado coalescido + resumen final
        assertTrue(sentMessages.get(0).contains("\"messageType\":\"STATE_UPDATE\""));
        assertTrue(sentMessages.get(0).contains("\"estadoSesion\":\"COMPLETADA\""));
        assertTrue(sentMessages.get(1).contains("\"messageType\":\"SESSION_SUMMARY\""));
        assertEquals(1, sentMessages.stream().filter(message ->
            message.contains("\"messageType\":\"SESSION_SUMMARY\"")).count());
        notifier.cerrarEnviadores();
    }

    @Test
    void completedHospitalPilotNeverPublishesClinicalApproval() {
        HandwashingSession session = new HandwashingSession("oms-pilot-informational", ProtocolType.DOMESTICO,
            new com.handwash.strategy.DomesticStrategy(), 1_500L, 650L, true);
        long timestamp = 10_000L;
        for (OmsAction action : OmsAction.SECUENCIA) {
            Map<String, SoapEvidence> evidence = new LinkedHashMap<>();
            for (SoapRegion region : SoapRegion.values()) {
                if (region.getAccionVerificacion() == action) {
                    evidence.put(region.name(), new SoapEvidence(
                        SoapEvidenceStatus.ESPUMA_VISIBLE, 0.95f));
                }
            }
            for (int observation = 0; observation < 8; observation++) {
                session.procesarAccionOms(action, evidence, timestamp, 0.95f);
                timestamp += 800L;
            }
        }

        assertEquals(HandwashingSessionState.COMPLETADA, session.getEstadoSesion());
        assertTrue(session.isProcedimientoCompletoValidado(),
            "La evidencia del procedimiento y la autorización clínica son dimensiones distintas");

        Notifier notifier = new Notifier(new SessionManager(new Receiver()));
        Map<String, Object> summary = notifier.crearResumen("oms-pilot-informational", session);
        notifier.cerrarEnviadores();

        assertEquals(false, summary.get("clinicalDecisionAllowed"));
        assertEquals(false, summary.get("aprobado"));
        assertEquals("SECUENCIA_OMS_COMPLETADA_SIN_AUTORIZACION_CLINICA", summary.get("resultado"));
    }

    @Test
    void mailboxKeepsOnlyTheNewestPendingStateWhileAWriteIsBlocked() throws Exception {
        SessionManager manager = new SessionManager(new Receiver());
        String id = manager.crearSesion(ProtocolType.DOMESTICO);
        Notifier notifier = new Notifier(manager);
        CountDownLatch firstSendStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstSend = new CountDownLatch(1);
        CountDownLatch twoMessagesSent = new CountDownLatch(2);
        List<String> sent = Collections.synchronizedList(new ArrayList<>());
        WebSocketSession client = (WebSocketSession) Proxy.newProxyInstance(
            WebSocketSession.class.getClassLoader(), new Class<?>[]{WebSocketSession.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "isOpen" -> true;
                case "getId" -> "coalescing-client";
                case "sendMessage" -> {
                    String payload = ((TextMessage) args[0]).getPayload();
                    sent.add(payload);
                    if (firstSendStarted.getCount() > 0) {
                        firstSendStarted.countDown();
                        releaseFirstSend.await(2, TimeUnit.SECONDS);
                    }
                    twoMessagesSent.countDown();
                    yield null;
                }
                case "close" -> null;
                default -> throw new UnsupportedOperationException(method.getName());
            });
        notifier.registrarSesion(id, client);

        try {
            notifier.enviarEstado(id, state(id, "PASO_1_PALMAS"));
            assertTrue(firstSendStarted.await(1, TimeUnit.SECONDS));
            notifier.enviarEstado(id, state(id, "PASO_2_DORSOS"));
            notifier.enviarEstado(id, state(id, "PASO_3_INTERDIGITALES"));
            releaseFirstSend.countDown();
            assertTrue(twoMessagesSent.await(1, TimeUnit.SECONDS));
            assertEquals(2, sent.size());
            assertTrue(sent.get(0).contains("PASO_1_PALMAS"));
            assertTrue(sent.get(1).contains("PASO_3_INTERDIGITALES"));
            assertFalse(sent.get(1).contains("PASO_2_DORSOS"));
        } finally {
            releaseFirstSend.countDown();
            notifier.cerrarEnviadores();
        }
    }

    @Test
    void stateUpdateArrivingDuringSnapshotSerializationSurvivesForTheNextFlush() throws Exception {
        CountDownLatch oldSnapshotCaptured = new CountDownLatch(1);
        CountDownLatch releaseSnapshot = new CountDownLatch(1);
        AtomicInteger snapshots = new AtomicInteger();
        AtomicReference<String> stateName = new AtomicReference<>("PASO_1_PALMAS");
        HandwashingSession session = new HandwashingSession("race", ProtocolType.DOMESTICO) {
            @Override
            public synchronized HandwashingStatusResponse getEstadoActualResponse() {
                String capturedName = stateName.get();
                if (snapshots.getAndIncrement() == 0) {
                    oldSnapshotCaptured.countDown();
                    try {
                        releaseSnapshot.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                HandwashingStatusResponse response = new HandwashingStatusResponse();
                response.setSessionId("race");
                response.setMessageType("STATE_UPDATE");
                response.setEstadoActual(capturedName);
                response.setEstadoSesion("EN_PROGRESO");
                return response;
            }
        };
        SessionManager manager = new SessionManager(new Receiver()) {
            @Override
            public HandwashingSession getSesion(String sessionId) {
                return "race".equals(sessionId) ? session : super.getSesion(sessionId);
            }
        };
        Notifier notifier = new Notifier(manager);
        CountDownLatch twoStatesSent = new CountDownLatch(2);
        List<String> messages = Collections.synchronizedList(new ArrayList<>());
        WebSocketSession client = (WebSocketSession) Proxy.newProxyInstance(
            WebSocketSession.class.getClassLoader(), new Class<?>[]{WebSocketSession.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "isOpen" -> true;
                case "getId" -> "snapshot-race-client";
                case "sendMessage" -> {
                    messages.add(((TextMessage) args[0]).getPayload());
                    twoStatesSent.countDown();
                    yield null;
                }
                case "close" -> null;
                default -> throw new UnsupportedOperationException(method.getName());
            });
        notifier.registrarSesion("race", client);
        ExecutorService flusher = Executors.newSingleThreadExecutor();
        try {
            notifier.onStateChanged("race");
            Future<?> firstFlush = flusher.submit(notifier::vaciarEstadosPendientes);
            assertTrue(oldSnapshotCaptured.await(1, TimeUnit.SECONDS));
            stateName.set("PASO_2_DORSOS");
            notifier.onStateChanged("race");
            releaseSnapshot.countDown();
            firstFlush.get(1, TimeUnit.SECONDS);

            notifier.vaciarEstadosPendientes();
            assertTrue(twoStatesSent.await(1, TimeUnit.SECONDS));
            assertEquals(2, messages.size());
            assertTrue(messages.get(0).contains("PASO_1_PALMAS"));
            assertTrue(messages.get(1).contains("PASO_2_DORSOS"));
        } finally {
            releaseSnapshot.countDown();
            flusher.shutdownNow();
            notifier.cerrarEnviadores();
        }
    }

    @Test
    void timedOutSlowClientCanReconnectAndReceiveRetainedTerminalSummary() throws Exception {
        SessionManager manager = new SessionManager(new Receiver());
        String id = manager.crearSesion(ProtocolType.DOMESTICO);
        HandwashingSession session = manager.getSesion(id);
        session.expirar();
        Notifier notifier = new Notifier(manager);
        ReflectionTestUtils.setField(notifier, "sendTimeoutMs", 1L);
        CountDownLatch slowSendStarted = new CountDownLatch(1);
        CountDownLatch slowSendReleased = new CountDownLatch(1);
        AtomicBoolean slowOpen = new AtomicBoolean(true);
        WebSocketSession slowClient = (WebSocketSession) Proxy.newProxyInstance(
            WebSocketSession.class.getClassLoader(), new Class<?>[]{WebSocketSession.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "isOpen" -> slowOpen.get();
                case "getId" -> "timed-out-client";
                case "sendMessage" -> {
                    slowSendStarted.countDown();
                    slowSendReleased.await(2, TimeUnit.SECONDS);
                    yield null;
                }
                case "close" -> {
                    slowOpen.set(false);
                    slowSendReleased.countDown();
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
        notifier.registrarSesion(id, slowClient);

        try {
            notifier.enviarResumenesExpirados();
            assertTrue(slowSendStarted.await(1, TimeUnit.SECONDS));
            Thread.sleep(10L);
            notifier.vaciarEstadosPendientes();
            assertTrue(slowSendReleased.await(1, TimeUnit.SECONDS));
            assertFalse(slowOpen.get());

            CountDownLatch reconnectMessages = new CountDownLatch(2);
            List<String> replayed = Collections.synchronizedList(new ArrayList<>());
            WebSocketSession reconnect = (WebSocketSession) Proxy.newProxyInstance(
                WebSocketSession.class.getClassLoader(), new Class<?>[]{WebSocketSession.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "isOpen" -> true;
                    case "getId" -> "reconnected-client";
                    case "sendMessage" -> {
                        replayed.add(((TextMessage) args[0]).getPayload());
                        reconnectMessages.countDown();
                        yield null;
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
            notifier.registrarSesion(id, reconnect);
            notifier.enviarInstantanea(id, session, reconnect);
            assertTrue(reconnectMessages.await(1, TimeUnit.SECONDS));
            assertTrue(replayed.get(0).contains("\"estadoSesion\":\"EXPIRADA\""));
            assertTrue(replayed.get(1).contains("\"messageType\":\"SESSION_SUMMARY\""));
        } finally {
            notifier.cerrarEnviadores();
        }
    }

    @Test
    void outboundClientMailboxesHaveAnExplicitConnectionLimit() {
        Notifier notifier = new Notifier(new SessionManager(new Receiver()));
        ReflectionTestUtils.setField(notifier, "maxOutboundClients", 1);
        try {
            assertTrue(notifier.registrarSesion("first", idleClient("first-client")));
            assertFalse(notifier.registrarSesion("second", idleClient("second-client")));
        } finally {
            notifier.cerrarEnviadores();
        }
    }

    @Test
    void saturatedSenderQueueRetainsLatestStateAndTerminalPairWithoutCrossSessionMixing() throws Exception {
        SessionManager manager = new SessionManager(new Receiver());
        ReflectionTestUtils.setField(manager, "maxSessions", 256);
        ReflectionTestUtils.setField(manager, "maxActiveSessions", 256);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Notifier notifier = new Notifier(manager, new HandwashMetrics(registry));
        CountDownLatch blockingWritersStarted = new CountDownLatch(4);
        CountDownLatch releaseBlockingWriters = new CountDownLatch(1);
        CountDownLatch fillerMessagesSent = new CountDownLatch(128);
        CountDownLatch deferredMessageSent = new CountDownLatch(1);
        CountDownLatch terminalMessagesSent = new CountDownLatch(2);
        List<String> deferredMessages = Collections.synchronizedList(new ArrayList<>());
        List<String> terminalMessages = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger crossedSessionMessages = new AtomicInteger();

        try {
            for (int index = 0; index < 4; index++) {
                String sessionId = manager.crearSesion(ProtocolType.DOMESTICO);
                WebSocketSession blocking = messageClient(sessionId, payload -> {
                    if (!payload.contains("\"sessionId\":\"" + sessionId + "\"")) {
                        crossedSessionMessages.incrementAndGet();
                    }
                    blockingWritersStarted.countDown();
                    try {
                        if (!releaseBlockingWriters.await(3, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("blocked writer test timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                }, () -> {});
                assertTrue(notifier.registrarSesion(sessionId, blocking));
                notifier.enviarEstado(sessionId, state(sessionId, "PASO_1_PALMAS"));
            }
            assertTrue(blockingWritersStarted.await(2, TimeUnit.SECONDS));

            var senderPool = (java.util.concurrent.ThreadPoolExecutor)
                ReflectionTestUtils.getField(notifier, "outboundExecutor");
            assertNotNull(senderPool);
            assertEquals(4, senderPool.getPoolSize());
            assertEquals(0, senderPool.getQueue().size());

            for (int index = 0; index < 128; index++) {
                String sessionId = manager.crearSesion(ProtocolType.DOMESTICO);
                WebSocketSession queued = messageClient(sessionId, payload -> {
                    if (!payload.contains("\"sessionId\":\"" + sessionId + "\"")) {
                        crossedSessionMessages.incrementAndGet();
                    }
                    fillerMessagesSent.countDown();
                }, () -> {});
                assertTrue(notifier.registrarSesion(sessionId, queued));
                notifier.enviarEstado(sessionId, state(sessionId, "PASO_1_PALMAS"));
            }
            assertEquals(128, senderPool.getQueue().size());
            assertEquals(128.0, registry.find("handwash.websocket.executor.queue.size").gauge().value());

            String deferredId = manager.crearSesion(ProtocolType.DOMESTICO);
            WebSocketSession deferred = messageClient(deferredId, payload -> {
                if (!payload.contains("\"sessionId\":\"" + deferredId + "\"")) {
                    crossedSessionMessages.incrementAndGet();
                }
                deferredMessages.add(payload);
                deferredMessageSent.countDown();
            }, () -> {});
            assertTrue(notifier.registrarSesion(deferredId, deferred));
            notifier.enviarEstado(deferredId, state(deferredId, "PASO_1_PALMAS"));
            notifier.enviarEstado(deferredId, state(deferredId, "PASO_2_DORSOS"));

            String terminalId = manager.crearSesion(ProtocolType.DOMESTICO);
            manager.getSesion(terminalId).expirar();
            WebSocketSession terminal = messageClient(terminalId, payload -> {
                if (!payload.contains("\"sessionId\":\"" + terminalId + "\"")) {
                    crossedSessionMessages.incrementAndGet();
                }
                terminalMessages.add(payload);
                terminalMessagesSent.countDown();
            }, () -> {});
            assertTrue(notifier.registrarSesion(terminalId, terminal));
            notifier.enviarResumenesExpirados();

            assertEquals(128, senderPool.getQueue().size(),
                "Rejected tasks must remain in per-client bounded mailboxes, not expand the executor queue");
            assertTrue(senderPool.getQueue().size() <= 128);
            assertTrue(registry.counter("handwash.websocket.executor.queue.rejected").count() > 0);

            releaseBlockingWriters.countDown();
            assertTrue(fillerMessagesSent.await(3, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while ((deferredMessageSent.getCount() > 0 || terminalMessagesSent.getCount() > 0)
                && System.nanoTime() < deadline) {
                notifier.vaciarEstadosPendientes();
                Thread.sleep(5L);
            }
            assertTrue(deferredMessageSent.await(1, TimeUnit.SECONDS));
            assertTrue(terminalMessagesSent.await(1, TimeUnit.SECONDS));
            assertEquals(1, deferredMessages.size());
            assertTrue(deferredMessages.get(0).contains("PASO_2_DORSOS"));
            assertFalse(deferredMessages.get(0).contains("PASO_1_PALMAS"));
            assertEquals(2, terminalMessages.size());
            assertTrue(terminalMessages.get(0).contains("\"estadoSesion\":\"EXPIRADA\""));
            assertTrue(terminalMessages.get(1).contains("\"messageType\":\"SESSION_SUMMARY\""));
            assertEquals(0, crossedSessionMessages.get());
            assertEquals(4, senderPool.getPoolSize());
            assertTrue(senderPool.getQueue().size() <= 128);
        } finally {
            releaseBlockingWriters.countDown();
            notifier.cerrarEnviadores();
        }
    }

    private HandwashingStatusResponse state(String sessionId, String step) {
        HandwashingStatusResponse response = new HandwashingStatusResponse();
        response.setSessionId(sessionId);
        response.setMessageType("STATE_UPDATE");
        response.setEstadoActual(step);
        response.setEstadoSesion("EN_PROGRESO");
        return response;
    }

    private WebSocketSession idleClient(String id) {
        return messageClient(id, ignored -> {}, () -> {});
    }

    private WebSocketSession messageClient(String id, Consumer<String> onMessage, Runnable onClose) {
        return (WebSocketSession) Proxy.newProxyInstance(
            WebSocketSession.class.getClassLoader(), new Class<?>[]{WebSocketSession.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "isOpen" -> true;
                case "getId" -> id;
                case "sendMessage" -> {
                    onMessage.accept(((TextMessage) args[0]).getPayload());
                    yield null;
                }
                case "close" -> {
                    onClose.run();
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    @Test
    void blockedClientDoesNotBlockOtherSessionsAndFinalStatePrecedesSummary() throws Exception {
        SessionManager manager = new SessionManager(new Receiver());
        String slowId = manager.crearSesion(ProtocolType.DOMESTICO);
        String fastId = manager.crearSesion(ProtocolType.DOMESTICO);
        manager.getSesion(slowId).expirar();
        manager.getSesion(fastId).expirar();
        Notifier notifier = new Notifier(manager);
        CountDownLatch slowSendStarted = new CountDownLatch(1);
        CountDownLatch releaseSlowSend = new CountDownLatch(1);
        CountDownLatch fastMessages = new CountDownLatch(2);
        CountDownLatch slowMessages = new CountDownLatch(2);
        List<String> slowPayloads = Collections.synchronizedList(new ArrayList<>());
        List<String> fastPayloads = Collections.synchronizedList(new ArrayList<>());
        WebSocketSession slowClient = (WebSocketSession) Proxy.newProxyInstance(
            WebSocketSession.class.getClassLoader(), new Class<?>[]{WebSocketSession.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "isOpen" -> true;
                case "getId" -> "slow-client";
                case "sendMessage" -> {
                    slowPayloads.add(((TextMessage) args[0]).getPayload());
                    slowSendStarted.countDown();
                    try {
                        if (!releaseSlowSend.await(3, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test slow sender timed out");
                        }
                    } finally {
                        slowMessages.countDown();
                    }
                    yield null;
                }
                case "close" -> null;
                default -> throw new UnsupportedOperationException(method.getName());
            });
        WebSocketSession fastClient = (WebSocketSession) Proxy.newProxyInstance(
            WebSocketSession.class.getClassLoader(), new Class<?>[]{WebSocketSession.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "isOpen" -> true;
                case "getId" -> "fast-client";
                case "sendMessage" -> {
                    fastPayloads.add(((TextMessage) args[0]).getPayload());
                    fastMessages.countDown();
                    yield null;
                }
                case "close" -> null;
                default -> throw new UnsupportedOperationException(method.getName());
            });
        notifier.registrarSesion(slowId, slowClient);
        notifier.registrarSesion(fastId, fastClient);

        try {
            notifier.enviarResumenesExpirados();
            assertTrue(slowSendStarted.await(1, TimeUnit.SECONDS));
            assertTrue(fastMessages.await(1, TimeUnit.SECONDS),
                "Una escritura bloqueada no debe retrasar otra sesión ni su resumen terminal");
            assertTrue(fastPayloads.get(0).contains("\"messageType\":\"STATE_UPDATE\""));
            assertTrue(fastPayloads.get(1).contains("\"messageType\":\"SESSION_SUMMARY\""));
        } finally {
            releaseSlowSend.countDown();
            assertTrue(slowMessages.await(1, TimeUnit.SECONDS));
            notifier.cerrarEnviadores();
        }
        assertTrue(slowPayloads.get(0).contains("\"messageType\":\"STATE_UPDATE\""));
        assertTrue(slowPayloads.get(1).contains("\"messageType\":\"SESSION_SUMMARY\""));
    }
}
