package com.handwash.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.handwash.service.SessionManager;
import com.handwash.agent.Receptor;
import com.handwash.agent.Notificador;
import com.handwash.model.EstadoLavadoResponse;
import com.handwash.model.DeteccionEvento;
import com.handwash.observer.DeteccionObserver;
import com.handwash.model.EstadoSesion;
import com.handwash.model.TipoInfraccion;
import com.handwash.model.PasoLavado;
import com.handwash.model.TipoProtocolo;
import com.handwash.strategy.ReglaValidacionStrategyFactory;
import com.handwash.api.v1.dto.EvaluationResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.WebSocketSession;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Meter;

import java.net.URI;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "handwash.inference.api-enabled=true",
        "handwash.oms.input-enabled=true",
        "handwash.intention.hand-presence-warmup-ms=0",
        "handwash.security.session-create-per-window=200",
        "handwash.security.session-pair-per-window=200"
    })
@AutoConfigureTestRestTemplate
class DetectionApiIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @Autowired SessionManager manager;
    @Autowired Receptor receptor;
    @Autowired Notificador notificador;
    @Autowired MeterRegistry meterRegistry;
    @Autowired ReglaValidacionStrategyFactory strategyFactory;
    @LocalServerPort int port;
    private final Set<String> producerSessionsToCleanup = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @AfterEach
    void cleanProducerSessions() {
        for (String id : producerSessionsToCleanup) {
            manager.eliminarSesion(id);
            notificador.eliminarSesion(id);
        }
        producerSessionsToCleanup.clear();
    }

    @Test
    void developmentBackendDoesNotServeStalePackagedDashboardAssets() {
        ResponseEntity<String> response = http.getForEntity("/", String.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void whoCatalogListsAllSoapAndWaterActionsInOrderWithoutClaimingModelCoverage() throws Exception {
        ResponseEntity<String> response = http.getForEntity("/api/protocols/oms", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode body = mapper.readTree(response.getBody());
        JsonNode actions = body.get("acciones");
        var expectedCodes = java.util.List.of(
            "OMS_01_MOJAR_MANOS", "OMS_02_APLICAR_JABON", "OMS_03_FROTAR_PALMAS",
            "OMS_04_FROTAR_DORSOS", "OMS_05_FROTAR_ENTRE_DEDOS",
            "OMS_06_FROTAR_DORSO_DE_DEDOS", "OMS_07_FROTAR_PULGARES",
            "OMS_08_FROTAR_PUNTAS_DE_DEDOS", "OMS_09_ENJUAGAR_MANOS",
            "OMS_10_SECAR_TOALLA_DESECHABLE", "OMS_11_CERRAR_GRIFO_CON_TOALLA");
        assertEquals(expectedCodes.size(), actions.size());
        assertTrue(body.get("nota").asText().contains("no indica"));
        assertEquals(40_000, body.get("duracionReferenciaMinMs").asInt());
        assertEquals(60_000, body.get("duracionReferenciaMaxMs").asInt());
        assertEquals(
            "https://cdn.who.int/media/docs/default-source/patient-safety/como-lavarse-las-manos.pdf?sfvrsn=7004a09d_11",
            body.get("fuente").asText());
        for (int index = 0; index < actions.size(); index++) {
            assertEquals(expectedCodes.get(index), actions.get(index).get("codigo").asText());
            assertEquals(expectedCodes.get(index), actions.get(index).get("claseModeloRequerida").asText());
            assertEquals(index + 1, actions.get(index).get("orden").asInt());
            assertFalse(actions.get(index).get("instruccion").asText().isBlank());
        }
        assertEquals("Frotar el dorso de los dedos contra la palma opuesta, agarrando los dedos.",
            actions.get(5).get("instruccion").asText());
        assertEquals("Frotar las puntas de los dedos de una mano contra la palma opuesta con movimiento de rotación, y viceversa.",
            actions.get(7).get("instruccion").asText());
    }

    @Test
    void malformedVersionedLoginJsonUsesStableApiErrorDto() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = http.exchange("/api/v1/auth/login", HttpMethod.POST,
            new HttpEntity<>("{", headers), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        JsonNode body = mapper.readTree(response.getBody());
        assertEquals("SOLICITUD_INVALIDA", body.get("error").asText());
        assertTrue(body.hasNonNull("mensaje"));
        assertFalse(body.has("trace"));
    }

    @Test
    void oversizedDetectionBodyIsRejectedAtHttpBoundary() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String oversizedJson = " ".repeat(16 * 1024 + 1);

        ResponseEntity<String> response = http.exchange("/api/v1/deteccion", HttpMethod.POST,
            new HttpEntity<>(oversizedJson, headers), String.class);

        assertEquals(413, response.getStatusCode().value());
        assertTrue(response.getBody().contains("CARGA_EXCEDE_LIMITE"));
    }

    @Test
    void oversizedLoginBodyIsRejectedBeforeJsonDeserialization() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String oversizedJson = " ".repeat(64 * 1024 + 1);

        ResponseEntity<String> response = http.exchange("/api/v1/auth/login", HttpMethod.POST,
            new HttpEntity<>(oversizedJson, headers), String.class);

        assertEquals(413, response.getStatusCode().value());
        assertTrue(response.getBody().contains("CARGA_EXCEDE_LIMITE"));
    }

    @Test
    void versionedApiLogsInRotatesDeviceTokenAndMapsDetectionDto() throws Exception {
        ResponseEntity<String> created = http.postForEntity("/api/v1/session", Map.of(
            "protocolo", "DOMESTICO", "producerProtocolVersion", "2"), String.class);
        assertEquals(HttpStatus.OK, created.getStatusCode());
        assertEquals("no-store", created.getHeaders().getCacheControl(),
            "responses containing OWNER credentials must not be cached");
        JsonNode createdBody = mapper.readTree(created.getBody());
        String id = createdBody.get("sessionId").asText();
        producerSessionsToCleanup.add(id);

        Instant ownerExpiry = Instant.parse(createdBody.get("accessTokenExpiresAt").asText());
        long ownerTtl = ownerExpiry.toEpochMilli() - System.currentTimeMillis();
        assertTrue(ownerTtl > 86_398_000L && ownerTtl <= 86_400_000L,
            "new OWNER token should have a one-day maximum lifetime");
        String previousDeviceToken = manager.getDeviceToken(id);

        ResponseEntity<String> legacyLogin = http.postForEntity("/api/v1/auth/login", Map.of(
            "code", createdBody.get("pairingCode").asText(), "producerProtocolVersion", "1"), String.class);
        assertEquals(HttpStatus.CONFLICT, legacyLogin.getStatusCode(),
            "a v2 session must never issue a credential to a legacy producer");
        assertEquals("CAPTURADOR_PROTOCOL_VERSION_REQUIRED",
            mapper.readTree(legacyLogin.getBody()).get("error").asText());

        ResponseEntity<String> login = http.postForEntity("/api/v1/auth/login", Map.of(
            "code", createdBody.get("pairingCode").asText(),
            "producerProtocolVersion", "2"), String.class);
        assertEquals(HttpStatus.OK, login.getStatusCode());
        assertEquals("no-store", login.getHeaders().getCacheControl(),
            "responses containing DEVICE credentials must not be cached");
        JsonNode loginBody = mapper.readTree(login.getBody());
        assertEquals("DEVICE", loginBody.get("role").asText());
        assertEquals("Bearer", loginBody.get("tokenType").asText());
        assertEquals(id, loginBody.get("sessionId").asText());
        Instant deviceExpiry = Instant.parse(loginBody.get("accessTokenExpiresAt").asText());
        long deviceTtl = deviceExpiry.toEpochMilli() - System.currentTimeMillis();
        assertTrue(deviceTtl > 86_398_000L && deviceTtl <= 86_400_000L,
            "login DEVICE token should expire within one day");
        assertTrue(manager.tieneAcceso(id, createdBody.get("accessToken").asText(), true));
        assertFalse(manager.tieneAcceso(id, previousDeviceToken, false),
            "new device login must revoke the prior device token");
        String deviceToken = loginBody.get("accessToken").asText();
        assertTrue(manager.tieneAcceso(id, deviceToken, false));

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + previousDeviceToken);
        ResponseEntity<String> staleEpochRegistration = http.exchange(
            "/api/v1/session/" + id + "/producer-epoch", HttpMethod.POST,
            new HttpEntity<>(Map.of(), headers), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, staleEpochRegistration.getStatusCode(),
            "a revoked device token must not register or replace a producer epoch");

        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + deviceToken);
        ResponseEntity<String> epochResponse = http.exchange(
            "/api/v1/session/" + id + "/producer-epoch", HttpMethod.POST,
            new HttpEntity<>(Map.of(), headers), String.class);
        assertEquals(HttpStatus.OK, epochResponse.getStatusCode());
        String epoch = mapper.readTree(epochResponse.getBody()).get("producerEpoch").asText();
        seedMotion(id, epoch);

        Map<String, Object> event = Map.of(
            "sessionId", id,
            "claseDetectada", "PASO_1_PALMAS",
            "confianza", 0.95,
            "timestamp", Instant.now().toString(),
            "producerEpoch", epoch,
            "eventType", "DETECTION",
            "frameSequence", 1,
            "captureAgeMs", 35,
            "evidenciaMovimiento", Map.of(
                "secuencia", 1,
                "manosVisibles", 2,
                "movimientoNormalizado", 0.2,
                "medicionValida", true,
                "antiguedadMs", 35,
                "poseKeypoints", bilateralPose(1L).get("poseKeypoints"),
                "handBoxes", bilateralPose(1L).get("handBoxes"),
                "frameWidth", 640,
                "frameHeight", 480));
        ResponseEntity<String> detection = http.exchange("/api/v1/deteccion", HttpMethod.POST,
            new HttpEntity<>(event, headers), String.class);
        assertEquals(HttpStatus.OK, detection.getStatusCode());
        assertTrue(mapper.readTree(detection.getBody()).get("accepted").asBoolean());

        ResponseEntity<String> bearerSession = http.exchange("/api/v1/session/" + id,
            HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.OK, bearerSession.getStatusCode());

        headers.remove(HttpHeaders.AUTHORIZATION);
        headers.set("X-Session-Token", deviceToken);
        ResponseEntity<String> legacySession = http.exchange("/api/v1/session/" + id,
            HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.OK, legacySession.getStatusCode(),
            "the camera and dashboard legacy header remains supported");

        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + createdBody.get("accessToken").asText());
        ResponseEntity<String> conflictingCredentials = http.exchange("/api/v1/session/" + id,
            HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, conflictingCredentials.getStatusCode(),
            "two different credentials must not be resolved by precedence");
    }

    @Test
    void httpProducerRequiresBilateralDwellAndPalmsIntentBeforeStarting() throws Exception {
        long previousWarmupMs = (long) ReflectionTestUtils.getField(
            manager, "handPresenceWarmupMs");
        ReflectionTestUtils.setField(manager, "handPresenceWarmupMs", 3_000L);
        ProducerSession producer = null;
        DeteccionObserver observer = null;
        AtomicInteger publishedSteps = new AtomicInteger();
        try {
            producer = createProducerSession();
            String producerId = producer.id();
            observer = event -> {
                if (producerId.equals(event.getSessionId())) publishedSteps.incrementAndGet();
            };
            receptor.addObserver(observer);
            assertAck(cameraPost(producer.id(), producer.token(),
                producerPresence(producer, 1L, 1L)), true);
            assertAck(cameraPost(producer.id(), producer.token(),
                producerEvent(producer, producer.epoch(), 2L, 2L)), true);
            assertEquals(0, publishedSteps.get(),
                "a step before the three-second dwell must not reach the observer pipeline");
            assertNotEquals(EstadoSesion.EN_PROGRESO,
                manager.getSesion(producer.id()).getEstadoSesion(),
                "hands alone must not start the wash");

            for (int pulse = 0; pulse < 31; pulse++) {
                Thread.sleep(100L);
                assertAck(cameraPost(producer.id(), producer.token(), producerPresence(
                    producer, pulse + 2L, pulse + 3L)), true);
            }

            for (int observation = 0; observation < 4; observation++) {
                if (observation > 0) {
                    Thread.sleep(325L);
                    assertAck(cameraPost(producer.id(), producer.token(), producerPresence(
                        producer, 32L + observation, 33L + observation * 2L)), true);
                }
                long sequence = 34L + observation * 2L;
                assertAck(cameraPost(producer.id(), producer.token(),
                    producerEvent(producer, producer.epoch(), sequence, sequence)), observation != 0);
            }
            assertEquals(3, publishedSteps.get(),
                "only confirmed palms observations should enter the pipeline after warmup");
            assertEquals(EstadoSesion.EN_PROGRESO,
                manager.getSesion(producer.id()).getEstadoSesion());

            assertAck(cameraPost(producer.id(), producer.token(),
                producerPresence(producer, 36L, 41L, 1)), true);
            assertAck(cameraPost(producer.id(), producer.token(),
                producerEvent(producer, producer.epoch(), 42L, 42L)), true);
            assertEquals(3, publishedSteps.get(),
                "one-hand presence must block step evidence even after the initial dwell");

            assertAck(cameraPost(producer.id(), producer.token(),
                producerPresence(producer, 37L, 43L, 2)), true);
            assertAck(cameraPost(producer.id(), producer.token(),
                producerEvent(producer, producer.epoch(), 44L, 44L)), false);
            Thread.sleep(100L);
            assertAck(cameraPost(producer.id(), producer.token(),
                producerEvent(producer, producer.epoch(), 46L, 46L)), true);
            assertEquals(4, publishedSteps.get(),
                "a fresh bilateral pose restores step inference without repeating initial dwell");
        } finally {
            if (observer != null) receptor.removeObserver(observer);
            ReflectionTestUtils.setField(manager, "handPresenceWarmupMs", previousWarmupMs);
        }
    }

    @Test
    void strictProducerCannotForgeMovementToStartWithStaticHands() throws Exception {
        ProducerSession producer = createProducerSession();
        try {
            for (long sequence = 1; sequence <= 4; sequence++) {
                Map<String, Object> event = producerEvent(
                    producer, producer.epoch(), sequence, sequence);
                @SuppressWarnings("unchecked")
                Map<String, Object> evidence = (Map<String, Object>) event.get("evidenciaMovimiento");
                evidence.put("movimientoNormalizado", 1.0);
                evidence.putAll(bilateralPose(0L));

                assertAck(cameraPost(producer.id(), producer.token(), event), true);
                if (sequence < 4) Thread.sleep(200L);
            }

            var session = manager.getSesion(producer.id());
            assertEquals(EstadoSesion.ESPERANDO_INICIO, session.getEstadoSesion());
            assertEquals(0L, session.getTiempoTotalActivoMs());
            assertEquals("MANOS_PRESENTES",
                session.getEstadoActualResponse().getEstadoIntencion());
        } finally {
            manager.eliminarSesion(producer.id());
        }
    }

    @Test
    void accessTokensAreRejectedAfterTheirConfiguredLifetime() throws Exception {
        long originalTtl = (long) ReflectionTestUtils.getField(manager, "accessTokenTtlMs");
        ReflectionTestUtils.setField(manager, "accessTokenTtlMs", 1_000L);
        String id = manager.crearSesion(TipoProtocolo.DOMESTICO);
        try {
            String token = manager.getOwnerToken(id);
            assertNotNull(token);
            assertTrue(manager.tieneAcceso(id, token, true));
            Thread.sleep(1_100L);
            assertFalse(manager.tieneAcceso(id, token, true));
            assertNull(manager.getOwnerToken(id));
        } finally {
            manager.eliminarSesion(id);
            ReflectionTestUtils.setField(manager, "accessTokenTtlMs", originalTtl);
        }
    }

    @Test
    void sessionDetectionValidationAndTerminalResponses() throws Exception {
        assertEquals(HttpStatus.BAD_REQUEST,
            http.postForEntity("/api/session", Map.of(), String.class).getStatusCode());
        ResponseEntity<String> created = http.postForEntity(
            "/api/session", Map.of("protocolo", "DOMESTICO"), String.class);
        assertEquals(HttpStatus.OK, created.getStatusCode());
        JsonNode createdBody = mapper.readTree(created.getBody());
        String id = createdBody.get("sessionId").asText();
        String pairingCode = createdBody.get("pairingCode").asText();
        assertFalse(pairingCode.isBlank());
        ResponseEntity<String> paired = http.postForEntity(
            "/api/session/pair", Map.of("code", pairingCode), String.class);
        assertEquals(HttpStatus.OK, paired.getStatusCode());
        assertEquals(id, mapper.readTree(paired.getBody()).get("sessionId").asText());
        assertFalse(mapper.readTree(paired.getBody()).get("accessToken").asText().isBlank());

        assertEquals(HttpStatus.UNAUTHORIZED, http.postForEntity("/api/deteccion", Map.of(
            "sessionId", id,
            "claseDetectada", "Paso1_Palmas",
            "confianza", 0.9,
            "timestamp", Instant.now().toString()
        ), String.class).getStatusCode());

        assertEquals(HttpStatus.BAD_REQUEST, send(id, "Paso1_Palmas", "bad-timestamp").getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
            send(id, "Paso99_Inexistente", Instant.now().toString()).getStatusCode());

        ResponseEntity<String> filtered = authorizedPost(id, Map.of(
            "sessionId", id,
            "claseDetectada", "Paso1_Palmas",
            "confianza", 0.1,
            "timestamp", Instant.now().toString()
        ));
        assertEquals(HttpStatus.OK, filtered.getStatusCode());
        assertFalse(mapper.readTree(filtered.getBody()).get("accepted").asBoolean());

        ResponseEntity<String> accepted = send(id, "Paso1_Palmas", Instant.now().toString());
        assertEquals(HttpStatus.OK, accepted.getStatusCode());
        JsonNode result = mapper.readTree(accepted.getBody());
        assertTrue(result.get("accepted").asBoolean());
        // Transport acceptance does not imply evidence of washing intent.
        assertTrue(result.get("estado").get("estadoActual").isNull());
        assertEquals("ESPERANDO_INICIO", result.get("estado").get("estadoSesion").asText());
        assertEquals("SIN_EVIDENCIA", result.get("estado").get("estadoIntencion").asText());
        Set<String> evaluationDtoFields = java.util.Arrays.stream(
                EvaluationResponse.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .collect(java.util.stream.Collectors.toSet());
        Set<String> serializedEvaluationFields = new HashSet<>();
        serializedEvaluationFields.addAll(result.get("estado").propertyNames());
        assertEquals(evaluationDtoFields, serializedEvaluationFields,
            "includeState must serialize the stable v1 DTO, never the domain object");

        for (int index = 1; index <= 5; index++) {
            if (index > 1) Thread.sleep(350);
            accepted = authorizedPost(id, Map.of(
                "sessionId", id, "claseDetectada", "Paso1_Palmas", "confianza", .95,
                "timestamp", Instant.now().toString(),
                "evidenciaMovimiento", Map.of("secuencia", index, "manosVisibles", 2,
                    "movimientoNormalizado", .2, "medicionValida", true, "antiguedadMs", 100)));
            assertEquals(HttpStatus.OK, accepted.getStatusCode());
        }
        result = mapper.readTree(accepted.getBody());
        assertEquals("PASO_1_PALMAS", result.get("estado").get("estadoActual").asText());
        assertEquals("LAVADO_PROBABLE", result.get("estado").get("estadoIntencion").asText());

        manager.getSesion(id).expirar();
        assertEquals(HttpStatus.CONFLICT, send(id, "Paso1_Palmas", Instant.now().toString()).getStatusCode());
    }

    @Test
    void producerV2AcceptsIncreasingFramesAndKeepsTheExistingAckShape() throws Exception {
        ProducerSession producer = createProducerSession();

        ResponseEntity<String> first = cameraPost(producer.id(), producer.token(),
            producerEvent(producer, producer.epoch(), 1, null));
        Thread.sleep(50L);
        ResponseEntity<String> second = cameraPost(producer.id(), producer.token(),
            producerEvent(producer, producer.epoch(), 2, null));

        assertEquals(HttpStatus.OK, first.getStatusCode());
        assertEquals(HttpStatus.OK, second.getStatusCode());
        assertAck(first, true);
        assertAck(second, true);
        assertTrue(meterRegistry.find("handwash.producer.capture.age").timer().count() >= 2);
        assertTrue(globalCounter("handwash.producer.epoch.registrations") >= 1);
    }

    @Test
    void domainInvalidFrameConsumesSequenceAndCannotBeReplayedAfterCorrection() throws Exception {
        ProducerSession producer = createProducerSession();
        Map<String, Object> invalid = producerEvent(producer, producer.epoch(), 500, null);
        invalid.put("timestamp", "not-an-iso-timestamp");

        ResponseEntity<String> rejected = cameraPost(producer.id(), producer.token(), invalid);
        ResponseEntity<String> corrected = cameraPost(producer.id(), producer.token(),
            producerEvent(producer, producer.epoch(), 500, null));
        ResponseEntity<String> nextFrame = cameraPost(producer.id(), producer.token(),
            producerEvent(producer, producer.epoch(), 501, null));

        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode());
        assertAck(corrected, false);
        assertEquals("FRAME_SEQUENCE_DUPLICATE",
            corrected.getHeaders().getFirst("X-Producer-Rejection-Reason"));
        assertAck(nextFrame, false);
        Thread.sleep(50L);
        assertAck(cameraPost(producer.id(), producer.token(),
            producerEvent(producer, producer.epoch(), 502, null)), true);
    }

    @Test
    void disabledOmsRuntimeRejectsUnapprovedLabelsWithoutChangingSessionMode() throws Exception {
        ProducerSession producer = createProducerSession();
        boolean previous = (boolean) ReflectionTestUtils.getField(manager, "omsInputEnabled");
        ReflectionTestUtils.setField(manager, "omsInputEnabled", false);
        try {
            Map<String, Object> omsEvent = producerEvent(
                producer, producer.epoch(), 1, 1L);
            omsEvent.put("claseDetectada", "OMS_01_MOJAR_MANOS");

            ResponseEntity<String> response = cameraPost(producer.id(), producer.token(), omsEvent);

            assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
            assertEquals("MODO_DETECCION_INCOMPATIBLE",
                mapper.readTree(response.getBody()).get("error").asText());
            assertNull(manager.getSesion(producer.id()).getModoEvaluacionSeleccionado());
            assertEquals(EstadoSesion.ESPERANDO_INICIO,
                manager.getSesion(producer.id()).getEstadoSesion());
        } finally {
            ReflectionTestUtils.setField(manager, "omsInputEnabled", previous);
        }
    }

    @Test
    void duplicateAndOutOfOrderFramesAreFilteredWithoutReachingObservers() throws Exception {
        ProducerSession producer = createProducerSession();
        AtomicInteger observations = new AtomicInteger();
        DeteccionObserver observer = event -> {
            if (producer.id().equals(event.getSessionId())) observations.incrementAndGet();
        };
        receptor.addObserver(observer);
        try {
            ResponseEntity<String> accepted = cameraPost(producer.id(), producer.token(),
                producerEvent(producer, producer.epoch(), 10, null));
            ResponseEntity<String> duplicate = cameraPost(producer.id(), producer.token(),
                producerEvent(producer, producer.epoch(), 10, null));
            Map<String, Object> replayedAsAnotherClass =
                producerEvent(producer, producer.epoch(), 10, null);
            replayedAsAnotherClass.put("claseDetectada", "PASO_2_DORSOS");
            ResponseEntity<String> modifiedReplay = cameraPost(
                producer.id(), producer.token(), replayedAsAnotherClass);
            ResponseEntity<String> old = cameraPost(producer.id(), producer.token(),
                producerEvent(producer, producer.epoch(), 9, null));

            assertAck(accepted, true);
            assertAck(duplicate, false);
            assertAck(modifiedReplay, false);
            assertEquals("FRAME_SEQUENCE_DUPLICATE",
                modifiedReplay.getHeaders().getFirst("X-Producer-Rejection-Reason"));
            assertAck(old, false);
            assertEquals(1, observations.get());
            assertTrue(counter("frame_sequence_duplicate") >= 1);
            assertTrue(counter("frame_sequence_out_of_order") >= 1);
            assertTrue(meterRegistry.find("handwash.producer.capture.age").timer().count() >= 3);
            for (Meter meter : meterRegistry.getMeters()) {
                if (!meter.getId().getName().startsWith("handwash.producer.")) continue;
                assertTrue(meter.getId().getTags().stream().noneMatch(tag ->
                    tag.getKey().equalsIgnoreCase("sessionId")
                        || tag.getKey().toLowerCase().contains("token")
                        || tag.getKey().toLowerCase().contains("frame")
                        || tag.getKey().toLowerCase().contains("sequence")));
            }
        } finally {
            receptor.removeObserver(observer);
        }
    }

    @Test
    void rotatingProducerEpochImmediatelyRejectsThePreviousProcess() throws Exception {
        ProducerSession oldProcess = createProducerSession();
        assertAck(cameraPost(oldProcess.id(), oldProcess.token(),
            producerEvent(oldProcess, oldProcess.epoch(), 4, null)), true);

        String newEpoch = registerEpoch(oldProcess.id(), oldProcess.token());
        assertNotEquals(oldProcess.epoch(), newEpoch);
        ResponseEntity<String> stale = cameraPost(oldProcess.id(), oldProcess.token(),
            producerEvent(oldProcess, oldProcess.epoch(), 5, null));
        assertAck(stale, false);
        assertEquals("EPOCH_MISMATCH", stale.getHeaders().getFirst("X-Producer-Rejection-Reason"));
        assertAck(cameraPost(oldProcess.id(), oldProcess.token(),
            producerEvent(oldProcess, newEpoch, 1, null)), true);
        assertTrue(counter("epoch_mismatch") >= 1);
        assertTrue(globalCounter("handwash.producer.epoch.rotations") >= 1);
    }

    @Test
    void eventAndSpatialEvidenceSequenceMismatchHasNoObserverSideEffect() throws Exception {
        ProducerSession producer = createProducerSession();
        AtomicInteger observations = new AtomicInteger();
        DeteccionObserver observer = event -> {
            if (producer.id().equals(event.getSessionId())) observations.incrementAndGet();
        };
        receptor.addObserver(observer);
        try {
            ResponseEntity<String> mismatch = cameraPost(producer.id(), producer.token(),
                producerEvent(producer, producer.epoch(), 5, 6L));
            assertAck(mismatch, false);
            assertEquals(0, observations.get());
            assertTrue(counter("evidence_sequence_mismatch") >= 1);

            ResponseEntity<String> corrected = cameraPost(producer.id(), producer.token(),
                producerEvent(producer, producer.epoch(), 5, 5L));
            assertAck(corrected, true);
            assertEquals(1, observations.get());
        } finally {
            receptor.removeObserver(observer);
        }
    }

    @Test
    void strictProducerRejectsLegacyClassAliasesWithoutPublishingOrConsumingFrame() throws Exception {
        ProducerSession producer = createProducerSession();
        AtomicInteger observations = new AtomicInteger();
        DeteccionObserver observer = event -> {
            if (producer.id().equals(event.getSessionId())) observations.incrementAndGet();
        };
        receptor.addObserver(observer);
        try {
            Map<String, Object> legacyName = producerEvent(producer, producer.epoch(), 31, null);
            legacyName.put("claseDetectada", "Paso1_Palmas");
            ResponseEntity<String> rejected = cameraPost(producer.id(), producer.token(), legacyName);
            assertAck(rejected, false);
            assertEquals("NON_CANONICAL_CLASS",
                rejected.getHeaders().getFirst("X-Producer-Rejection-Reason"));
            assertEquals(0, observations.get());

            Map<String, Object> semanticAlias = producerEvent(producer, producer.epoch(), 31, null);
            semanticAlias.put("claseDetectada", "Paso3_PalmaDorsoDedos");
            ResponseEntity<String> wrongSemanticLabel = cameraPost(
                producer.id(), producer.token(), semanticAlias);
            assertAck(wrongSemanticLabel, false);
            assertEquals("NON_CANONICAL_CLASS",
                wrongSemanticLabel.getHeaders().getFirst("X-Producer-Rejection-Reason"));
            assertEquals(0, observations.get());

            Map<String, Object> canonical = producerEvent(producer, producer.epoch(), 31, null);
            canonical.put("claseDetectada", "PASO_1_PALMAS");
            assertAck(cameraPost(producer.id(), producer.token(), canonical), true);
            assertEquals(1, observations.get());
        } finally {
            receptor.removeObserver(observer);
        }
    }

    @Test
    void soapEvidenceSequenceMismatchIsFilteredWithoutConsumingTheFrame() throws Exception {
        ProducerSession producer = createProducerSession();
        Map<String, Object> evidence = Map.of(
            "PALMA_IZQUIERDA", Map.of("estado", "ESPUMA_VISIBLE", "confianza", 0.95));
        AtomicInteger observations = new AtomicInteger();
        DeteccionObserver observer = event -> {
            if (producer.id().equals(event.getSessionId())) observations.incrementAndGet();
        };
        receptor.addObserver(observer);
        try {
            Map<String, Object> mismatched = new java.util.LinkedHashMap<>(
                producerEvent(producer, producer.epoch(), 5, 5L));
            mismatched.put("claseDetectada", "OMS_03_FROTAR_PALMAS");
            mismatched.put("evidenciaJabon", evidence);
            mismatched.put("evidenciaJabonSecuencia", 4L);

            ResponseEntity<String> rejected = cameraPost(
                producer.id(), producer.token(), mismatched);
            assertAck(rejected, false);
            assertEquals("SOAP_EVIDENCE_SEQUENCE_MISMATCH",
                rejected.getHeaders().getFirst("X-Producer-Rejection-Reason"));
            assertEquals(0, observations.get());

            mismatched.put("evidenciaJabonSecuencia", 5L);
            assertAck(cameraPost(producer.id(), producer.token(), mismatched), true);
            assertEquals(1, observations.get(),
                "correcting the evidence must allow retry of the unconsumed frame sequence");
        } finally {
            receptor.removeObserver(observer);
        }
    }

    @Test
    void sessionsHaveIndependentEpochsAndFrameWatermarks() throws Exception {
        ProducerSession first = createProducerSession();
        ProducerSession second = createProducerSession();
        assertNotEquals(first.epoch(), second.epoch());

        ExecutorService clients = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ResponseEntity<String>> firstResponse = clients.submit(() -> {
                start.await();
                return cameraPost(first.id(), first.token(),
                    producerEvent(first, first.epoch(), 1, null));
            });
            Future<ResponseEntity<String>> secondResponse = clients.submit(() -> {
                start.await();
                return cameraPost(second.id(), second.token(),
                    producerEvent(second, second.epoch(), 1, null));
            });
            start.countDown();
            assertAck(firstResponse.get(3, TimeUnit.SECONDS), true);
            assertAck(secondResponse.get(3, TimeUnit.SECONDS), true);
        } finally {
            clients.shutdownNow();
        }
        String rotated = registerEpoch(first.id(), first.token());
        assertAck(cameraPost(first.id(), first.token(),
            producerEvent(first, first.epoch(), 2, null)), false);
        assertAck(cameraPost(first.id(), first.token(),
            producerEvent(first, rotated, 1, null)), true);
        Thread.sleep(50L);
        assertAck(cameraPost(second.id(), second.token(),
            producerEvent(second, second.epoch(), 2, null)), true);
        assertNotNull(manager.getSesion(first.id()));
        assertNotNull(manager.getSesion(second.id()));
    }

    @Test
    void controlWatermarkRejectsAnInflightFrameAndControlRetriesAreIdempotent() throws Exception {
        ProducerSession producer = createProducerSession();
        Map<String, Object> control = new java.util.LinkedHashMap<>(Map.of(
            "sessionId", producer.id(), "claseDetectada", "FONDO", "confianza", 1.0,
            "timestamp", Instant.now().toString(), "producerEpoch", producer.epoch(),
            "eventType", "CONTROL", "controlSequence", 1, "frameWatermark", 10,
            "captureAgeMs", 8));
        assertAck(cameraPost(producer.id(), producer.token(), control), true);
        assertAck(cameraPost(producer.id(), producer.token(), control), false);
        Map<String, Object> invalidControlClass = new java.util.LinkedHashMap<>(control);
        invalidControlClass.put("claseDetectada", "PASO_1_PALMAS");
        invalidControlClass.put("controlSequence", 2);
        assertAck(cameraPost(producer.id(), producer.token(), invalidControlClass), false);
        Map<String, Object> oldControl = new java.util.LinkedHashMap<>(control);
        oldControl.put("controlSequence", 3);
        oldControl.put("frameWatermark", 9);
        assertAck(cameraPost(producer.id(), producer.token(), oldControl), false);
        assertAck(cameraPost(producer.id(), producer.token(),
            producerEvent(producer, producer.epoch(), 10, null)), false);
        assertAck(cameraPost(producer.id(), producer.token(),
            producerEvent(producer, producer.epoch(), 11, null)), false);
        Thread.sleep(50L);
        assertAck(cameraPost(producer.id(), producer.token(),
            producerEvent(producer, producer.epoch(), 12, null)), true);
        assertTrue(counter("control_sequence_duplicate") >= 1);
        assertTrue(counter("frame_watermark_out_of_order") >= 1);
        assertTrue(counter("control_class_invalid") >= 1);
    }

    @Test
    void controlEnvelopeCannotCarrySoapEvidenceOrMutateTheSession() throws Exception {
        ProducerSession producer = createProducerSession();
        AtomicInteger observations = new AtomicInteger();
        DeteccionObserver observer = event -> {
            if (producer.id().equals(event.getSessionId())) observations.incrementAndGet();
        };
        receptor.addObserver(observer);
        Map<String, Object> control = new java.util.LinkedHashMap<>(Map.of(
            "sessionId", producer.id(), "claseDetectada", "OMS_SIN_EVIDENCIA", "confianza", 1.0,
            "timestamp", Instant.now().toString(), "producerEpoch", producer.epoch(),
            "eventType", "CONTROL", "controlSequence", 1, "frameWatermark", 0,
            "captureAgeMs", 8));
        control.put("evidenciaJabon", Map.of(
            "PALMA_IZQUIERDA", Map.of("estado", "ESPUMA_VISIBLE", "confianza", 0.95)));
        control.put("evidenciaJabonSecuencia", 0);
        try {
            ResponseEntity<String> rejected = cameraPost(producer.id(), producer.token(), control);
            assertAck(rejected, false);
            assertEquals("CONTROL_CARRIES_FRAME_EVIDENCE",
                rejected.getHeaders().getFirst("X-Producer-Rejection-Reason"));
            assertEquals(0, observations.get());

            control.remove("evidenciaJabon");
            control.remove("evidenciaJabonSecuencia");
            assertAck(cameraPost(producer.id(), producer.token(), control), true);
            assertEquals(1, observations.get(),
                "rejecting frame evidence must not consume the control sequence or publish an event");
        } finally {
            receptor.removeObserver(observer);
        }
    }

    @Test
    void registrationAndLateOldEventRaceIsSerializedByTheSessionLock() throws Exception {
        ProducerSession producer = createProducerSession();
        var session = manager.getSesion(producer.id());
        DeteccionEvento oldEvent = mapper.convertValue(
            producerEvent(producer, producer.epoch(), 2, null), DeteccionEvento.class);
        CountDownLatch attempting = new CountDownLatch(1);
        AtomicReference<SessionManager.DetectionResult> result = new AtomicReference<>();
        Thread delayed = new Thread(() -> {
            attempting.countDown();
            result.set(manager.procesarDeteccionHttp(oldEvent, producer.token()));
        }, "late-old-producer-frame-test");
        String newEpoch;
        synchronized (session) {
            delayed.start();
            assertTrue(attempting.await(2, TimeUnit.SECONDS));
            long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (delayed.getState() != Thread.State.BLOCKED && System.nanoTime() < waitUntil) {
                Thread.onSpinWait();
            }
            assertEquals(Thread.State.BLOCKED, delayed.getState());
            SessionManager.ProducerEpochResult registration =
                manager.registrarProducerEpoch(producer.id(), producer.token());
            assertEquals(SessionManager.ProducerEpochOutcome.REGISTERED, registration.outcome());
            newEpoch = registration.registration().producerEpoch();
        }
        delayed.join(2_000L);
        assertFalse(delayed.isAlive());
        assertNotEquals(producer.epoch(), newEpoch);
        assertEquals(SessionManager.DetectionOutcome.TRANSPORT_REJECTED, result.get().outcome());
        assertEquals(com.handwash.service.HandwashMetrics.ProducerRejectionReason.EPOCH_MISMATCH,
            result.get().rejectionReason());
    }

    @Test
    void expiredAndDeletedSessionsRejectLaterProducerPosts() throws Exception {
        ProducerSession expired = createProducerSession();
        synchronized (manager.getSesion(expired.id())) {
            manager.getSesion(expired.id()).expirar();
        }
        assertEquals(HttpStatus.CONFLICT, cameraPost(expired.id(), expired.token(),
            producerEvent(expired, expired.epoch(), 1, null)).getStatusCode());

        ProducerSession deleted = createProducerSession();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Session-Token", deleted.token());
        ResponseEntity<String> removal = http.exchange("/api/session/" + deleted.id(),
            HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.OK, removal.getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, cameraPost(deleted.id(), deleted.token(),
            producerEvent(deleted, deleted.epoch(), 1, null)).getStatusCode());
    }

    @Test
    void legacyCompatibilityIsExplicitAndV2RegistrationRequiresTheExistingToken() throws Exception {
        ResponseEntity<String> created = http.postForEntity("/api/session",
            Map.of("protocolo", "DOMESTICO"), String.class);
        JsonNode createdBody = mapper.readTree(created.getBody());
        String id = createdBody.get("sessionId").asText();
        String ownerToken = createdBody.get("accessToken").asText();
        producerSessionsToCleanup.add(id);

        Map<String, Object> legacy = Map.of("sessionId", id, "claseDetectada", "Paso1_Palmas",
            "confianza", 0.95, "timestamp", Instant.now().toString());
        ResponseEntity<String> legacyAccepted = cameraPost(id, ownerToken, legacy);
        assertAck(legacyAccepted, true);

        Map<String, Object> prematureV2 = Map.of("sessionId", id, "claseDetectada", "Paso1_Palmas",
            "confianza", 0.95, "timestamp", Instant.now().toString(),
            "producerEpoch", "not-registered", "eventType", "DETECTION", "frameSequence", 1);
        assertAck(cameraPost(id, ownerToken, prematureV2), false);

        HttpHeaders missingToken = new HttpHeaders();
        ResponseEntity<String> unauthorized = http.exchange("/api/session/" + id + "/producer-epoch",
            HttpMethod.POST, new HttpEntity<>(missingToken), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, unauthorized.getStatusCode());
        String epoch = registerEpoch(id, ownerToken);
        ProducerSession strict = new ProducerSession(id, ownerToken, epoch);
        assertAck(cameraPost(id, ownerToken, legacy), false);
        Map<String, Object> missingType = producerEvent(strict, epoch, 1, null);
        missingType.remove("eventType");
        assertAck(cameraPost(id, ownerToken, missingType), false);
        assertAck(cameraPost(id, ownerToken, producerEvent(strict, epoch, 1, null)), true);

        Set<String> keys = new HashSet<>();
        keys.addAll(mapper.readTree(legacyAccepted.getBody()).propertyNames());
        assertEquals(Set.of("accepted", "filtered"), keys);
    }

    @Test
    void pairingV2UpgradesTheSessionBeforeReturningTheDeviceCapability() throws Exception {
        ResponseEntity<String> created = http.postForEntity("/api/session",
            Map.of("protocolo", "DOMESTICO"), String.class);
        JsonNode body = mapper.readTree(created.getBody());
        String id = body.get("sessionId").asText();
        String pairingCode = body.get("pairingCode").asText();
        producerSessionsToCleanup.add(id);
        ResponseEntity<String> paired = http.postForEntity("/api/session/pair",
            Map.of("code", pairingCode, "producerProtocolVersion", "2"), String.class);
        assertEquals(HttpStatus.OK, paired.getStatusCode());
        String deviceToken = mapper.readTree(paired.getBody()).get("accessToken").asText();
        assertFalse(deviceToken.isBlank());
        ResponseEntity<String> legacyPair = http.postForEntity("/api/session/pair",
            Map.of("code", pairingCode), String.class);
        assertEquals(HttpStatus.CONFLICT, legacyPair.getStatusCode());
        assertEquals("CAPTURADOR_PROTOCOL_VERSION_REQUIRED",
            mapper.readTree(legacyPair.getBody()).get("error").asText());

        Map<String, Object> legacy = Map.of("sessionId", id, "claseDetectada", "Paso1_Palmas",
            "confianza", 0.95, "timestamp", Instant.now().toString());
        assertAck(cameraPost(id, deviceToken, legacy), false);
        String epoch = registerEpoch(id, deviceToken);
        assertAck(cameraPost(id, deviceToken,
            producerEvent(new ProducerSession(id, deviceToken, epoch), epoch, 1, null)), true);
    }

    @Test
    void v2PairingInvalidatesAnAlreadyRunningProducerBeforeReturningTheNewToken() throws Exception {
        ProducerSession oldProcess = createProducerSession();
        String pairingCode = manager.getCodigoEmparejamiento(oldProcess.id());
        ResponseEntity<String> paired = http.postForEntity("/api/session/pair",
            Map.of("code", pairingCode, "producerProtocolVersion", "2"), String.class);
        assertEquals(HttpStatus.OK, paired.getStatusCode());
        String newDeviceToken = mapper.readTree(paired.getBody()).get("accessToken").asText();

        assertAck(cameraPost(oldProcess.id(), oldProcess.token(),
            producerEvent(oldProcess, oldProcess.epoch(), 2, null)), false);
        String newEpoch = registerEpoch(oldProcess.id(), newDeviceToken);
        assertNotEquals(oldProcess.epoch(), newEpoch);
        assertTrue(globalCounter("handwash.producer.epoch.rotations") >= 1);
    }

    private ProducerSession createProducerSession() throws Exception {
        ResponseEntity<String> created = http.postForEntity("/api/session", Map.of(
            "protocolo", "DOMESTICO", "producerProtocolVersion", "2"), String.class);
        assertEquals(HttpStatus.OK, created.getStatusCode());
        JsonNode body = mapper.readTree(created.getBody());
        String id = body.get("sessionId").asText();
        String token = body.get("accessToken").asText();
        producerSessionsToCleanup.add(id);
        return new ProducerSession(id, token, registerEpoch(id, token));
    }

    private String registerEpoch(String id, String token) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Session-Token", token);
        ResponseEntity<String> response = http.exchange("/api/session/" + id + "/producer-epoch",
            HttpMethod.POST, new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode body = mapper.readTree(response.getBody());
        assertEquals(2, body.get("protocolVersion").asInt());
        String epoch = body.get("producerEpoch").asText();
        seedMotion(id, epoch);
        return epoch;
    }

    private Map<String, Object> producerEvent(ProducerSession producer, String epoch,
                                               long frameSequence, Long evidenceSequence) {
        Map<String, Object> event = new java.util.LinkedHashMap<>(Map.of(
            "sessionId", producer.id(),
            "claseDetectada", "PASO_1_PALMAS",
            "confianza", 0.95,
            "timestamp", Instant.now().toString(),
            "producerEpoch", epoch,
            "eventType", "DETECTION",
            "frameSequence", frameSequence,
            "captureAgeMs", 7
        ));
        Map<String, Object> evidence = new java.util.LinkedHashMap<>(Map.of(
            "secuencia", evidenceSequence == null ? frameSequence : evidenceSequence,
            "manosVisibles", 2,
            "movimientoNormalizado", 0.2,
            "medicionValida", true,
            "antiguedadMs", 7));
        evidence.putAll(bilateralPose(frameSequence));
        event.put("evidenciaMovimiento", evidence);
        return event;
    }

    private void seedMotion(String sessionId, String epoch) {
        var estimator = (com.handwash.service.OpenCvHandMotionEstimator)
            ReflectionTestUtils.getField(manager, "handMotionEstimator");
        var geometry = bilateralPose(0L);
        estimator.observe(sessionId, epoch, 0L, System.nanoTime() - 200_000_000L,
            new com.handwash.model.EvidenciaPoseManos(
                (Double[][][]) geometry.get("poseKeypoints"),
                (Double[][]) geometry.get("handBoxes"), 640, 480));
    }

    private static Map<String, Object> bilateralPose(long frameSequence) {
        Double[][][] points = new Double[2][21][3];
        Double[][] boxes = new Double[2][4];
        double firstHandShift = 2.0 * Math.sin(frameSequence * Math.PI / 4.0);
        for (int hand = 0; hand < 2; hand++) {
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            for (int point = 0; point < 21; point++) {
                double x = 100 + (point % 5) * 10 + hand * 65;
                double y = 100 + (point / 5) * 10 + (hand == 0 ? firstHandShift : 0.0);
                points[hand][point] = new Double[] {x, y, 0.95};
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
            boxes[hand] = new Double[] {minX - 4, minY - 4, maxX + 4, maxY + 4};
        }
        return Map.of("poseKeypoints", points, "handBoxes", boxes,
            "frameWidth", 640, "frameHeight", 480);
    }

    private Map<String, Object> producerPresence(ProducerSession producer,
                                                 long controlSequence, long frameWatermark) {
        return producerPresence(producer, controlSequence, frameWatermark, 2);
    }

    private Map<String, Object> producerPresence(ProducerSession producer,
                                                 long controlSequence, long frameWatermark,
                                                 int handsVisible) {
        return Map.of(
            "sessionId", producer.id(),
            "claseDetectada", "PRESENCIA_MANOS",
            "confianza", 1.0,
            "timestamp", Instant.now().toString(),
            "producerEpoch", producer.epoch(),
            "eventType", "PRESENCE",
            "controlSequence", controlSequence,
            "frameWatermark", frameWatermark,
            "captureAgeMs", 0,
            "presenceHandsVisible", handsVisible
        );
    }

    private ResponseEntity<String> cameraPost(String id, String token, Map<String, ?> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Session-Token", token);
        return http.exchange("/api/deteccion", HttpMethod.POST,
            new HttpEntity<>(body, headers), String.class);
    }

    private void assertAck(ResponseEntity<String> response, boolean accepted) throws Exception {
        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode body = mapper.readTree(response.getBody());
        assertEquals(accepted, body.get("accepted").asBoolean());
        assertEquals(!accepted, body.get("filtered").asBoolean());
        Set<String> keys = new HashSet<>();
        keys.addAll(body.propertyNames());
        assertEquals(Set.of("accepted", "filtered"), keys);
    }

    private void assertEvaluationDtoShape(JsonNode evaluation) {
        Set<String> expected = java.util.Arrays.stream(EvaluationResponse.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .collect(java.util.stream.Collectors.toSet());
        Set<String> actual = new HashSet<>();
        actual.addAll(evaluation.propertyNames());
        assertEquals(expected, actual,
            "REST must serialize the versioned EvaluationResponse DTO, not the domain object");
    }

    private double counter(String reason) {
        var counter = meterRegistry.find("handwash.producer.rejections")
            .tags("reason", reason).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double globalCounter(String name) {
        var counter = meterRegistry.find(name).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private record ProducerSession(String id, String token, String epoch) {}

    @Test
    void storesOnlyFailedAttemptSummariesAndProtectsTheirEndpoint() throws Exception {
        ResponseEntity<String> created = http.postForEntity(
            "/api/session", Map.of("protocolo", "DOMESTICO"), String.class);
        String id = mapper.readTree(created.getBody()).get("sessionId").asText();
        String ownerToken = manager.getOwnerToken(id);

        for (int sequence = 1; sequence <= 5; sequence++) {
            if (sequence > 1) Thread.sleep(350L);
            ResponseEntity<String> accepted = authorizedPost(id, Map.of(
                "sessionId", id,
                "claseDetectada", "Paso1_Palmas",
                "confianza", .95,
                "timestamp", Instant.now().toString(),
                "evidenciaMovimiento", Map.of("secuencia", sequence, "manosVisibles", 2,
                    "movimientoNormalizado", .2, "medicionValida", true, "antiguedadMs", 100)));
            assertEquals(HttpStatus.OK, accepted.getStatusCode());
        }

        ResponseEntity<String> skipped = authorizedPost(id, Map.of(
            "sessionId", id,
            "claseDetectada", "Paso3_Interdigitales",
            "confianza", .95,
            "timestamp", Instant.now().toString(),
            "evidenciaMovimiento", Map.of("secuencia", 6, "manosVisibles", 2,
                "movimientoNormalizado", .2, "medicionValida", true, "antiguedadMs", 100)));
        assertEquals(HttpStatus.OK, skipped.getStatusCode());
        Thread.sleep(350L);
        ResponseEntity<String> confirmedSkip = authorizedPost(id, Map.of(
            "sessionId", id,
            "claseDetectada", "Paso3_Interdigitales",
            "confianza", .95,
            "timestamp", Instant.now().toString(),
            "evidenciaMovimiento", Map.of("secuencia", 7, "manosVisibles", 2,
                "movimientoNormalizado", .2, "medicionValida", true, "antiguedadMs", 100)));
        assertEquals(HttpStatus.OK, confirmedSkip.getStatusCode());

        assertEquals(HttpStatus.UNAUTHORIZED,
            http.getForEntity("/api/session/" + id + "/attempts", String.class).getStatusCode());
        HttpHeaders ownerHeaders = new HttpHeaders();
        ownerHeaders.set("X-Session-Token", ownerToken);
        ResponseEntity<String> history = http.exchange("/api/session/" + id + "/attempts",
            HttpMethod.GET, new HttpEntity<>(ownerHeaders), String.class);
        assertEquals(HttpStatus.OK, history.getStatusCode());
        JsonNode attempts = mapper.readTree(history.getBody()).get("intentosFallidos");
        assertEquals(1, attempts.size());
        assertEquals("PASO_FUERA_DE_SECUENCIA", attempts.get(0).get("motivoReinicio").asText());
        assertTrue(attempts.get(0).has("numero"));
        assertTrue(attempts.get(0).has("resultado"));
        assertTrue(attempts.get(0).has("duracionMs"));
        assertTrue(attempts.get(0).get("tiempoPorPasoMs").isObject());
        assertTrue(attempts.get(0).get("infracciones").isArray());
        assertTrue(mapper.readTree(history.getBody()).get("soloMetadatos").asBoolean());

        ResponseEntity<String> deleted = http.exchange("/api/session/" + id,
            HttpMethod.DELETE, new HttpEntity<>(ownerHeaders), String.class);
        assertEquals(HttpStatus.OK, deleted.getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
            http.getForEntity("/api/session/" + id + "/attempts", String.class).getStatusCode());
    }

    @Test
    void omsHttpPipelineTracksSoapEvidenceAndRestartsOnRisk() throws Exception {
        String id = manager.crearSesion(TipoProtocolo.DOMESTICO);
        try {
            Map<String, Object> soapEvidence = Map.of(
                "PALMA_IZQUIERDA", Map.of("estado", "ESPUMA_VISIBLE", "confianza", 0.95));
            Map<String, Object> staleSoapEvent = omsEvent(
                id, "OMS_02_APLICAR_JABON", 3L, soapEvidence);
            staleSoapEvent.put("evidenciaJabonSecuencia", 2L);
            assertEquals(HttpStatus.BAD_REQUEST, authorizedPost(id, staleSoapEvent).getStatusCode(),
                "regional foam evidence from a different camera frame must not be credited");

            ResponseEntity<String> wet = authorizedPost(id,
                omsEvent(id, "OMS_01_MOJAR_MANOS", 1L, null));
            assertEquals(HttpStatus.OK, wet.getStatusCode());
            JsonNode wetState = mapper.readTree(wet.getBody()).get("estado");
            assertEquals("OMS_01_MOJAR_MANOS", wetState.get("estadoActual").asText());
            assertEquals("PROTOCOLO_OMS", wetState.get("modoEvaluacion").asText());
            assertFalse(wetState.get("procedimientoCompletoValidado").asBoolean());
            assertEquals(40_000L, wetState.get("duracionMinimaObjetivoMs").asLong());
            assertEquals(HttpStatus.CONFLICT,
                send(id, "Paso1_Palmas", Instant.now().toString()).getStatusCode());

            Thread.sleep(650L);
            authorizedPost(id, omsEvent(id, "OMS_01_MOJAR_MANOS", 2L, null));
            Map<String, Object> soapEvent = omsEvent(id, "OMS_02_APLICAR_JABON", 3L, soapEvidence);
            ResponseEntity<String> soapFirst = authorizedPost(id, soapEvent);
            assertEquals(HttpStatus.OK, soapFirst.getStatusCode());
            JsonNode firstCoverage = mapper.readTree(soapFirst.getBody()).get("estado").get("coberturaJabon");
            assertEquals("NO_VERIFICABLE", firstCoverage.get("PALMA_IZQUIERDA").asText());

            Thread.sleep(650L);
            authorizedPost(id, omsEvent(id, "OMS_02_APLICAR_JABON", 4L, soapEvidence));
            Map<String, Object> palmsEvent = omsEvent(id, "OMS_03_FROTAR_PALMAS", 5L, soapEvidence);
            ResponseEntity<String> palmFirst = authorizedPost(id, palmsEvent);
            assertEquals(HttpStatus.OK, palmFirst.getStatusCode());
            JsonNode palmCandidateState = mapper.readTree(palmFirst.getBody()).get("estado");
            assertEquals("OMS_02_APLICAR_JABON", palmCandidateState.get("estadoActual").asText());
            assertEquals("OMS_03_FROTAR_PALMAS", palmCandidateState.get("claseCandidata").asText());
            Thread.sleep(20L);
            ResponseEntity<String> palmSecond = authorizedPost(id,
                omsEvent(id, "OMS_03_FROTAR_PALMAS", 6L, soapEvidence));
            assertEquals(HttpStatus.OK, palmSecond.getStatusCode());
            JsonNode secondState = mapper.readTree(palmSecond.getBody()).get("estado");
            assertEquals("OMS_03_FROTAR_PALMAS", secondState.get("estadoActual").asText());
            assertTrue(secondState.get("claseCandidata").isNull());
            JsonNode secondCoverage = secondState.get("coberturaJabon");
            assertEquals("ESPUMA_VISIBLE", secondCoverage.get("PALMA_IZQUIERDA").asText(),
                palmSecond.getBody());

            Map<String, Object> riskEvent = omsEvent(id, "OMS_CONTACTO_RIESGO", 7L, null);
            riskEvent.remove("evidenciaMovimiento");
            ResponseEntity<String> risk = authorizedPost(id, riskEvent);
            assertEquals(HttpStatus.OK, risk.getStatusCode());
            JsonNode reset = mapper.readTree(risk.getBody()).get("estado");
            assertEquals("ESPERANDO_INICIO", reset.get("estadoSesion").asText());
            assertEquals(1, reset.get("intentosReiniciados").asInt());
            assertEquals("CONTACTO_RIESGO", reset.get("infraccion").get("tipo").asText());
            assertFalse(reset.get("coberturaJabonCompleta").asBoolean());

            ResponseEntity<String> repeatedRisk = authorizedPost(id,
                omsEvent(id, "OMS_CONTACTO_RIESGO", 8L, null));
            assertEquals(1, mapper.readTree(repeatedRisk.getBody())
                .get("estado").get("intentosReiniciados").asInt());

            authorizedPost(id, omsEvent(id, "OMS_01_MOJAR_MANOS", 9L, null));
            ResponseEntity<String> lostObservation = send(id, "OMS_SIN_EVIDENCIA", Instant.now().toString());
            assertEquals(HttpStatus.OK, lostObservation.getStatusCode());
            JsonNode lostState = mapper.readTree(lostObservation.getBody()).get("estado");
            assertEquals("ESPERANDO_INICIO", lostState.get("estadoSesion").asText());
            assertEquals("EVIDENCIA_VISUAL_INTERRUPTA", lostState.get("infraccion").get("tipo").asText());
            assertEquals(2, lostState.get("intentosReiniciados").asInt());
        } finally {
            manager.eliminarSesion(id);
        }
    }

    @Test
    void activeDiscoveryRequiresPairingWhenMultipleSessionsExist() throws Exception {
        String first = manager.crearSesion(TipoProtocolo.DOMESTICO);
        String code = manager.getCodigoEmparejamiento(first);
        assertNotNull(code);

        String second = manager.crearSesion(TipoProtocolo.CLINICO_QUIRURGICO);
        try {
            ResponseEntity<String> ambiguous = http.getForEntity("/api/session/active", String.class);
            assertEquals(HttpStatus.CONFLICT, ambiguous.getStatusCode());
            assertEquals("SESIONES_ACTIVAS_AMBIGUAS", mapper.readTree(ambiguous.getBody()).get("error").asText());

            ResponseEntity<String> paired = http.postForEntity(
                "/api/session/pair", Map.of("code", code), String.class);
            assertEquals(HttpStatus.OK, paired.getStatusCode());
            assertEquals(first, mapper.readTree(paired.getBody()).get("sessionId").asText());
            assertEquals(HttpStatus.NOT_FOUND, http.postForEntity(
                "/api/session/pair", Map.of("code", "AAAAA-AAAAA"), String.class).getStatusCode());
            assertEquals(HttpStatus.BAD_REQUEST, http.postForEntity(
                "/api/session/pair", Map.of(), String.class).getStatusCode());
        } finally {
            manager.eliminarSesion(first);
            manager.eliminarSesion(second);
        }
    }

    @Test
    void pairedDeviceCanReadButCannotDeleteOwnersSession() throws Exception {
        String id = manager.crearSesion(TipoProtocolo.DOMESTICO);
        String code = manager.getCodigoEmparejamiento(id);
        String deviceToken = mapper.readTree(http.postForEntity(
            "/api/session/pair", Map.of("code", code), String.class).getBody())
            .get("accessToken").asText();
        HttpHeaders deviceHeaders = new HttpHeaders();
        deviceHeaders.set("X-Session-Token", deviceToken);
        try {
            assertEquals(HttpStatus.UNAUTHORIZED,
                http.getForEntity("/api/session/" + id, String.class).getStatusCode());
            ResponseEntity<String> detail = http.exchange("/api/session/" + id,
                HttpMethod.GET, new HttpEntity<>(deviceHeaders), String.class);
            assertEquals(HttpStatus.OK, detail.getStatusCode());
            JsonNode detailJson = mapper.readTree(detail.getBody());
            assertFalse(detailJson.has("pairingCode"));
            assertTrue(detailJson.has("evaluacion"));
            JsonNode evaluation = detailJson.get("evaluacion");
            assertEquals(id, evaluation.get("sessionId").asText());
            assertEquals("ESPERANDO_INICIO", evaluation.get("estadoSesion").asText());
            assertEquals(7, evaluation.get("progreso").get("pasosTotales").asInt());
            assertTrue(evaluation.has("historialInfracciones"));
            assertTrue(evaluation.has("coberturaJabon"));
            HttpHeaders ownerHeaders = new HttpHeaders();
            ownerHeaders.set("X-Session-Token", manager.getOwnerToken(id));
            ResponseEntity<String> ownerDetail = http.exchange("/api/v1/session/" + id,
                HttpMethod.GET, new HttpEntity<>(ownerHeaders), String.class);
            assertEquals(HttpStatus.OK, ownerDetail.getStatusCode());
            assertEquals(code, mapper.readTree(ownerDetail.getBody()).get("pairingCode").asText());
            assertEquals(HttpStatus.UNAUTHORIZED, http.exchange("/api/session/" + id,
                HttpMethod.DELETE, new HttpEntity<>(deviceHeaders), String.class).getStatusCode());
            assertNotNull(manager.getSesion(id));
        } finally {
            manager.eliminarSesion(id);
        }
    }

    @Test
    void dashboardViewerCanReadAndConnectWithoutRotatingTheActiveProducerEpoch() throws Exception {
        ProducerSession producer = createProducerSession();
        String pairingCode = manager.getCodigoEmparejamiento(producer.id());
        String existingDeviceToken = manager.getDeviceToken(producer.id());

        ResponseEntity<String> login = http.postForEntity("/api/v1/auth/dashboard-login",
            Map.of("code", pairingCode), String.class);
        assertEquals(HttpStatus.OK, login.getStatusCode());
        assertEquals("no-store", login.getHeaders().getCacheControl(),
            "responses containing VIEWER credentials must not be cached");
        JsonNode loginBody = mapper.readTree(login.getBody());
        assertEquals(producer.id(), loginBody.get("sessionId").asText());
        assertEquals("VIEWER", loginBody.get("role").asText());
        assertFalse(loginBody.has("producerProtocolVersion"));
        String viewerToken = loginBody.get("accessToken").asText();
        assertTrue(manager.tieneAccesoLectura(producer.id(), viewerToken));
        assertFalse(manager.tieneAcceso(producer.id(), viewerToken, false));
        assertTrue(manager.tieneAcceso(producer.id(), producer.token(), true));
        assertTrue(manager.tieneAcceso(producer.id(), existingDeviceToken, false),
            "dashboard pairing must not rotate the producer device token");

        HttpHeaders viewerHeaders = new HttpHeaders();
        viewerHeaders.set("X-Session-Token", viewerToken);
        ResponseEntity<String> detail = http.exchange("/api/v1/session/" + producer.id(),
            HttpMethod.GET, new HttpEntity<>(viewerHeaders), String.class);
        assertEquals(HttpStatus.OK, detail.getStatusCode());
        assertFalse(mapper.readTree(detail.getBody()).has("pairingCode"),
            "the dashboard viewer must not receive the session pairing secret");
        assertEquals(HttpStatus.OK, http.exchange("/api/v1/session/" + producer.id() + "/attempts",
            HttpMethod.GET, new HttpEntity<>(viewerHeaders), String.class).getStatusCode());

        assertEquals(HttpStatus.UNAUTHORIZED, http.exchange(
            "/api/v1/session/" + producer.id() + "/producer-epoch", HttpMethod.POST,
            new HttpEntity<>(Map.of(), viewerHeaders), String.class).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, cameraPost(producer.id(), viewerToken,
            producerEvent(producer, producer.epoch(), 1, null)).getStatusCode());
        MultiValueMap<String, Object> inferenceForm = new LinkedMultiValueMap<>();
        inferenceForm.add("file", new ByteArrayResource(new byte[]{1, 2, 3}) {
            @Override public String getFilename() { return "frame.jpg"; }
        });
        HttpHeaders inferenceHeaders = new HttpHeaders();
        inferenceHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        inferenceHeaders.set("X-Session-Token", viewerToken);
        assertEquals(HttpStatus.UNAUTHORIZED, http.exchange(
            "/api/v1/infer?session_id=" + producer.id(), HttpMethod.POST,
            new HttpEntity<>(inferenceForm, inferenceHeaders), String.class).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, http.exchange("/api/v1/session/" + producer.id(),
            HttpMethod.DELETE, new HttpEntity<>(viewerHeaders), String.class).getStatusCode());

        RecordingListener listener = new RecordingListener();
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .buildAsync(authorizedWsUri(producer.id(), viewerToken), listener).join();
        try {
            String initialState = listener.messages.poll(3, TimeUnit.SECONDS);
            assertNotNull(initialState, "the viewer should receive dashboard state over WebSocket");
            assertEquals(producer.id(), mapper.readTree(initialState).get("sessionId").asText());

            ResponseEntity<String> continuedDetection = cameraPost(producer.id(), producer.token(),
                producerEvent(producer, producer.epoch(), 1, null));
            assertAck(continuedDetection, true);
        } finally {
            socket.abort();
        }
    }

    @Test
    void inferEndpointRejectsMissingTokenBeforeInvokingGateway() {
        String id = manager.crearSesion(TipoProtocolo.DOMESTICO);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(new byte[]{1, 2, 3}) {
            @Override public String getFilename() { return "frame.jpg"; }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        try {
            ResponseEntity<String> response = http.postForEntity(
                "/api/infer?session_id=" + id, new HttpEntity<>(form, headers), String.class);
            assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        } finally {
            manager.eliminarSesion(id);
        }
    }

    @Test
    void inferEndpointRejectsAnonymousUploadWithoutCreatingASession() {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(new byte[]{1, 2, 3}) {
            @Override public String getFilename() { return "frame.jpg"; }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = http.postForEntity(
            "/api/infer", new HttpEntity<>(form, headers), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void unreadTomcatWebSocketDoesNotStallAnotherSessionAndRetainsTerminalOrder() throws Exception {
        String slowId = manager.crearSesion(TipoProtocolo.DOMESTICO);
        String fastId = manager.crearSesion(TipoProtocolo.DOMESTICO);
        Socket stalledClient = openUnreadWebSocket(slowId, manager.getOwnerToken(slowId));
        RecordingListener fastListener = new RecordingListener();
        WebSocket fastSocket = HttpClient.newHttpClient().newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .buildAsync(authorizedWsUri(fastId), fastListener).join();
        long oldSendTimeoutMs = (long) ReflectionTestUtils.getField(notificador, "sendTimeoutMs");
        WebSocketSession slowSession = null;
        Object slowWriter = null;
        try {
            assertNotNull(fastListener.messages.poll(3, TimeUnit.SECONDS), "initial fast-client state");
            slowSession = ((Map<String, Set<WebSocketSession>>)
                ReflectionTestUtils.getField(notificador, "sessions")).get(slowId).iterator().next();
            WebSocketSession connectedSlowSession = slowSession;
            slowWriter = ((Map<WebSocketSession, ?>)
                ReflectionTestUtils.getField(notificador, "outboundClients")).get(connectedSlowSession);
            assertNotNull(slowWriter);
            Object initialWriter = slowWriter;
            waitUntil(() -> !Boolean.TRUE.equals(ReflectionTestUtils.getField(initialWriter, "sendInFlight"))
                    && ReflectionTestUtils.getField(initialWriter, "latestState") == null
                    && !Boolean.TRUE.equals(ReflectionTestUtils.getField(initialWriter, "scheduled")),
                Duration.ofSeconds(2), "small initial snapshot should fit the unread client's receive buffer");
            ReflectionTestUtils.setField(notificador, "sendTimeoutMs", 1_500L);

            EstadoLavadoResponse largeState = new EstadoLavadoResponse();
            largeState.setSessionId(slowId);
            largeState.setMessageType("STATE_UPDATE");
            largeState.setEstadoSesion("EN_PROGRESO");
            // A text payload forces real Tomcat/TCP backpressure without creating
            // or persisting any image/video data.
            largeState.setMotivoIntencion("x".repeat(16 * 1024 * 1024));
            notificador.enviarEstado(slowId, largeState);
            boolean writeStayedBlocked = waitForBlockedWrite(slowWriter, Duration.ofSeconds(2));

            EstadoLavadoResponse fastState = new EstadoLavadoResponse();
            fastState.setSessionId(fastId);
            fastState.setMessageType("STATE_UPDATE");
            fastState.setEstadoSesion("EN_PROGRESO");
            fastState.setEstadoActual("FAST_SESSION_STATE");
            notificador.enviarEstado(fastId, fastState);
            String fastUpdate = fastListener.messages.poll(2, TimeUnit.SECONDS);
            assertNotNull(fastUpdate, "a stalled peer must not block a fast peer's state");
            assertTrue(fastUpdate.contains("FAST_SESSION_STATE"));

            manager.getSesion(fastId).expirar();
            notificador.enviarResumenesExpirados();
            String finalState = fastListener.messages.poll(2, TimeUnit.SECONDS);
            String finalSummary = fastListener.messages.poll(2, TimeUnit.SECONDS);
            assertNotNull(finalState);
            assertTrue(finalState.contains("\"estadoSesion\":\"EXPIRADA\""));
            assertNotNull(finalSummary);
            assertTrue(finalSummary.contains("\"messageType\":\"SESSION_SUMMARY\""));

            assertTrue(writeStayedBlocked,
                "the unread raw socket must reproduce a pending Tomcat async send for this regression test");
            waitUntil(() -> !((Map<WebSocketSession, ?>)
                ReflectionTestUtils.getField(notificador, "outboundClients")).containsKey(connectedSlowSession),
                Duration.ofSeconds(4), "slow Tomcat client must be closed at its send deadline");
            var senders = (java.util.concurrent.ThreadPoolExecutor)
                ReflectionTestUtils.getField(notificador, "outboundExecutor");
            waitUntil(() -> senders.getActiveCount() == 0, Duration.ofSeconds(3),
                "the timed-out async write must not occupy an application sender worker");
        } finally {
            ReflectionTestUtils.setField(notificador, "sendTimeoutMs", oldSendTimeoutMs);
            try { stalledClient.close(); } catch (IOException ignored) {}
            try { fastSocket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").join(); }
            catch (RuntimeException ignored) { fastSocket.abort(); }
            manager.eliminarSesion(slowId);
            manager.eliminarSesion(fastId);
        }
    }

    private Socket openUnreadWebSocket(String sessionId, String accessToken) throws IOException {
        URI websocketUri = authorizedWsUri(sessionId, accessToken);
        Socket socket = new Socket();
        socket.setReceiveBufferSize(1024);
        socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 3_000);
        socket.setSoTimeout(3_000);
        String key = Base64.getEncoder().encodeToString(new byte[16]);
        String request = "GET " + websocketUri.getRawPath() + "?" + websocketUri.getRawQuery() + " HTTP/1.1\r\n"
            + "Host: 127.0.0.1:" + port + "\r\n"
            + "Origin: http://127.0.0.1:5173\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Version: 13\r\n"
            + "Sec-WebSocket-Key: " + key + "\r\n\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();

        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int matched = 0;
        while (header.size() < 8_192 && matched < 4) {
            int value = socket.getInputStream().read();
            if (value < 0) throw new IOException("Tomcat closed before WebSocket upgrade completed");
            header.write(value);
            matched = switch (matched) {
                case 0 -> value == '\r' ? 1 : 0;
                case 1 -> value == '\n' ? 2 : value == '\r' ? 1 : 0;
                case 2 -> value == '\r' ? 3 : 0;
                case 3 -> value == '\n' ? 4 : 0;
                default -> matched;
            };
        }
        String response = header.toString(StandardCharsets.US_ASCII);
        if (!response.startsWith("HTTP/1.1 101")) {
            socket.close();
            throw new IOException("Tomcat rejected raw WebSocket upgrade: " + response);
        }
        socket.setSoTimeout(0);
        return socket; // Deliberately do not consume server WebSocket frames.
    }

    private void waitUntil(java.util.function.BooleanSupplier condition, Duration timeout,
                           String failureMessage) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10L);
        assertTrue(condition.getAsBoolean(), failureMessage);
    }

    private boolean waitForBlockedWrite(Object outbound, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            boolean inFlight = Boolean.TRUE.equals(ReflectionTestUtils.getField(outbound, "sendInFlight"));
            long started = (long) ReflectionTestUtils.getField(outbound, "sendingSinceNanos");
            if (inFlight) {
                Thread.sleep(250L);
                boolean stillInFlight = Boolean.TRUE.equals(
                    ReflectionTestUtils.getField(outbound, "sendInFlight"));
                long after = (long) ReflectionTestUtils.getField(outbound, "sendingSinceNanos");
                if (stillInFlight && after == started) return true;
            }
            Thread.sleep(10L);
        }
        return false;
    }

    private ResponseEntity<String> send(String id, String step, String timestamp) {
        return authorizedPost(id, Map.of(
            "sessionId", id,
            "claseDetectada", step,
            "confianza", 0.9,
            "timestamp", timestamp
        ));
    }

    private Map<String, Object> omsEvent(String id, String action, long sequence,
                                         Map<String, Object> soapEvidence) {
        Map<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("sessionId", id);
        event.put("claseDetectada", action);
        event.put("confianza", 0.95);
        event.put("timestamp", Instant.now().toString());
        event.put("evidenciaMovimiento", Map.of(
            "secuencia", sequence,
            "manosVisibles", 2,
            "movimientoNormalizado", 0.2,
            "medicionValida", true,
            "antiguedadMs", 100
        ));
        if (soapEvidence != null) {
            event.put("evidenciaJabon", soapEvidence);
            event.put("evidenciaJabonSecuencia", sequence);
        }
        return event;
    }

    private ResponseEntity<String> authorizedPost(String id, Map<String, ?> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Session-Token", manager.getOwnerToken(id));
        return http.exchange("/api/deteccion?includeState=true", HttpMethod.POST,
            new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void criticalValidationFailureCannotApproveSession() throws Exception {
        ResponseEntity<String> created = http.postForEntity(
            "/api/session", Map.of("protocolo", "DOMESTICO"), String.class);
        String id = mapper.readTree(created.getBody()).get("sessionId").asText();
        DeteccionObserver brokenValidator = event -> {
            throw new IllegalStateException("validation unavailable");
        };
        receptor.addCriticalObserver(brokenValidator);
        try {
            ResponseEntity<String> result = send(id, "Paso1_Palmas", Instant.now().toString());
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, result.getStatusCode());
            JsonNode failure = mapper.readTree(result.getBody());
            assertEquals("ERROR_PROCESAMIENTO", failure.get("error").asText());
            assertEvaluationDtoShape(failure.get("estado"));
            assertEquals(EstadoSesion.EXPIRADA, manager.getSesion(id).getEstadoSesion());
            assertEquals(TipoInfraccion.ERROR_PROCESAMIENTO,
                manager.getSesion(id).getHistorialInfracciones().get(0).getTipo());
        } finally {
            receptor.removeObserver(brokenValidator);
        }
    }

    @Test
    void publishedProtocolTimesMatchStrategies() throws Exception {
        ResponseEntity<String> response = http.getForEntity("/api/protocols", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode protocols = mapper.readTree(response.getBody());
        for (TipoProtocolo protocol : TipoProtocolo.values()) {
            JsonNode published = protocols.get(protocol.name());
            assertTrue(published.isObject());
            assertTrue(published.hasNonNull("nombre") && published.get("nombre").isTextual());
            assertTrue(published.hasNonNull("metodo_objetivo")
                && published.get("metodo_objetivo").isTextual());
            assertEquals("REGLA_DEL_PROYECTO_NO_UMBRAL_OMS",
                published.get("origen_tiempos_por_paso").asText());
            assertTrue(published.hasNonNull("alcance_evaluacion")
                && published.get("alcance_evaluacion").isTextual());
            assertTrue(published.get("procedimiento_completo_validado").isBoolean());
            assertFalse(published.get("procedimiento_completo_validado").asBoolean());
            assertTrue(published.get("acciones_no_detectadas").isArray());
            assertEquals(strategyFactory.crear(protocol).getDuracionTotalMs(),
                published.get("duracion_total_ms").asLong());
            Set<String> expectedSteps = new HashSet<>();
            for (PasoLavado step : PasoLavado.values()) {
                if (step != PasoLavado.FONDO) expectedSteps.add(step.name());
            }
            JsonNode publishedStepTimes = published.get("tiempos_por_paso");
            assertEquals(expectedSteps.size(), publishedStepTimes.size());
            for (String expectedStep : expectedSteps) {
                assertTrue(publishedStepTimes.has(expectedStep));
            }
            for (PasoLavado step : PasoLavado.values()) {
                if (step != PasoLavado.FONDO) {
                    assertEquals(strategyFactory.crear(protocol).getTiempoRequeridoPaso(step),
                        published.get("tiempos_por_paso").get(step.name()).asLong());
                }
            }
        }
    }

    @Test
    void websocketPublishesInitialStateAndRejectsInboundDetections() throws Exception {
        ResponseEntity<String> created = http.postForEntity(
            "/api/session", Map.of("protocolo", "DOMESTICO"), String.class);
        String id = mapper.readTree(created.getBody()).get("sessionId").asText();
        RecordingListener listener = new RecordingListener();
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .buildAsync(authorizedWsUri(id), listener).join();
        try {
            String initial = listener.messages.poll(3, TimeUnit.SECONDS);
            assertNotNull(initial);
            JsonNode initialState = mapper.readTree(initial);
            assertEquals(id, initialState.get("sessionId").asText());
            assertEquals("ESPERANDO_INICIO", initialState.get("estadoSesion").asText());
            socket.sendText(mapper.writeValueAsString(Map.of(
                "claseDetectada", "Paso1_Palmas",
                "confianza", 0.9,
                "timestamp", Instant.now().toString()
            )), true).join();
            assertEquals(1003, listener.closed.get(3, TimeUnit.SECONDS));
        } finally {
            // The server closes this output-only socket with 1003 after the
            // client attempts to send a detection; abort is safe after that close.
            socket.abort();
        }
    }

    @Test
    void websocketClosesAfterItsDeviceTokenIsRotatedByRelogin() throws Exception {
        ResponseEntity<String> created = http.postForEntity("/api/v1/session",
            Map.of("protocolo", "DOMESTICO"), String.class);
        assertEquals(HttpStatus.OK, created.getStatusCode());
        JsonNode createdBody = mapper.readTree(created.getBody());
        String id = createdBody.get("sessionId").asText();
        producerSessionsToCleanup.add(id);
        String pairingCode = createdBody.get("pairingCode").asText();

        ResponseEntity<String> firstLogin = http.postForEntity("/api/v1/auth/login",
            Map.of("code", pairingCode), String.class);
        assertEquals(HttpStatus.OK, firstLogin.getStatusCode());
        String firstDeviceToken = mapper.readTree(firstLogin.getBody()).get("accessToken").asText();

        RecordingListener listener = new RecordingListener();
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .buildAsync(authorizedWsUri(id, firstDeviceToken), listener).join();
        try {
            String initialState = listener.messages.poll(3, TimeUnit.SECONDS);
            assertNotNull(initialState);
            assertEquals(id, mapper.readTree(initialState).get("sessionId").asText());

            ResponseEntity<String> secondLogin = http.postForEntity("/api/v1/auth/login",
                Map.of("code", pairingCode), String.class);
            assertEquals(HttpStatus.OK, secondLogin.getStatusCode());
            String replacementDeviceToken = mapper.readTree(secondLogin.getBody())
                .get("accessToken").asText();
            assertFalse(manager.tieneAcceso(id, firstDeviceToken, false));
            assertTrue(manager.tieneAcceso(id, replacementDeviceToken, false));
            assertTrue(manager.tieneAcceso(id, createdBody.get("accessToken").asText(), true),
                "rotating a device token must not revoke the owner capability");

            assertEquals(1008, listener.closed.get(3, TimeUnit.SECONDS),
                "the next scheduled WebSocket flush must close the revoked capability");
        } finally {
            socket.abort();
        }
    }

    @Test
    void websocketRejectsUnknownSessionAtConnection() throws Exception {
        RecordingListener listener = new RecordingListener();
        HttpClient.newHttpClient().newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/missing"), listener).join();

        assertEquals(1008, listener.closed.get(3, TimeUnit.SECONDS));
    }

    @Test
    void websocketReconnectReceivesRetainedTerminalSummary() throws Exception {
        ResponseEntity<String> created = http.postForEntity(
            "/api/session", Map.of("protocolo", "DOMESTICO"), String.class);
        String id = mapper.readTree(created.getBody()).get("sessionId").asText();
        manager.getSesion(id).expirar();
        RecordingListener listener = new RecordingListener();
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
            .buildAsync(authorizedWsUri(id), listener).join();
        try {
            String state = listener.messages.poll(3, TimeUnit.SECONDS);
            String summary = listener.messages.poll(3, TimeUnit.SECONDS);
            assertNotNull(state);
            assertEquals("EXPIRADA", mapper.readTree(state).get("estadoSesion").asText());
            assertNotNull(summary);
            assertEquals("SESSION_SUMMARY", mapper.readTree(summary).get("messageType").asText());
            assertEquals("INCOMPLETO", mapper.readTree(summary).get("resultado").asText());
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").join();
        }
    }

    @Test
    void websocketTicketEndpointRequiresCurrentSessionCredential() throws Exception {
        String id = manager.crearSesion(TipoProtocolo.DOMESTICO);
        try {
            HttpHeaders invalidHeaders = new HttpHeaders();
            invalidHeaders.setBearerAuth("invalid-token");
            ResponseEntity<String> denied = http.exchange(
                "/api/v1/session/" + id + "/websocket-ticket", HttpMethod.POST,
                new HttpEntity<>(invalidHeaders), String.class);
            assertEquals(HttpStatus.UNAUTHORIZED, denied.getStatusCode());

            HttpHeaders ownerHeaders = new HttpHeaders();
            ownerHeaders.setBearerAuth(manager.getOwnerToken(id));
            ResponseEntity<String> issued = http.exchange(
                "/api/v1/session/" + id + "/websocket-ticket", HttpMethod.POST,
                new HttpEntity<>(ownerHeaders), String.class);
            assertEquals(HttpStatus.OK, issued.getStatusCode());
            JsonNode response = mapper.readTree(issued.getBody());
            assertEquals(43, response.get("ticket").asText().length());
            assertTrue(Instant.parse(response.get("expiresAt").asText())
                .isBefore(Instant.now().plusSeconds(31)));
            assertFalse(response.has("accessToken"));
        } finally {
            manager.eliminarSesion(id);
        }
    }

    @Test
    void websocketRejectsLegacyRawAccessTokenAndMissingTicket() throws Exception {
        String id = manager.crearSesion(TipoProtocolo.DOMESTICO);
        RecordingListener listener = new RecordingListener();
        try {
            HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/" + id
                    + "?access_token=" + manager.getOwnerToken(id)), listener).join();
            assertEquals(1008, listener.closed.get(3, TimeUnit.SECONDS));
        } finally {
            manager.eliminarSesion(id);
        }
    }

    private URI authorizedWsUri(String id) {
        return authorizedWsUri(id, manager.getOwnerToken(id));
    }

    private URI authorizedWsUri(String id, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<String> ticket = http.exchange(
            "/api/v1/session/" + id + "/websocket-ticket", HttpMethod.POST,
            new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.OK, ticket.getStatusCode());
        String ticketValue;
        try {
            ticketValue = mapper.readTree(ticket.getBody()).get("ticket").asText();
        } catch (Exception malformedResponse) {
            throw new AssertionError("WebSocket ticket endpoint returned invalid JSON", malformedResponse);
        }
        return URI.create("ws://127.0.0.1:" + port + "/ws/" + id
            + "?ticket=" + ticketValue);
    }

    private static class RecordingListener implements WebSocket.Listener {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final StringBuilder frame = new StringBuilder();

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            frame.append(data);
            if (last) {
                messages.add(frame.toString());
                frame.setLength(0);
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            closed.complete(statusCode);
            return null;
        }
    }
}
