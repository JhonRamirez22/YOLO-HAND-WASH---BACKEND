package com.handwash.controller;

import com.handwash.agent.Receptor;
import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.api.v1.dto.EvaluationResponse;
import com.handwash.api.v1.dto.InferenceResponse;
import com.handwash.api.v1.mapper.SessionResponseMapper;
import com.handwash.model.TipoProtocolo;
import com.handwash.security.SessionTokenResolver;
import com.handwash.service.ImagePayloadValidator;
import com.handwash.service.InferenceAdmissionGate;
import com.handwash.service.InferenceService;
import com.handwash.service.SessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InferenceControllerTest {
    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 16_000_000L;
    private static final MockMultipartFile IMAGE = new MockMultipartFile(
        "file", "frame.png", "image/png", pngImage());

    @Test
    void multipartInferenceControllerIsExplicitlyOptIn() {
        ConditionalOnProperty condition = InferenceController.class
            .getAnnotation(ConditionalOnProperty.class);
        assertNotNull(condition);
        assertEquals("handwash.inference", condition.prefix());
        assertArrayEquals(new String[]{"api-enabled"}, condition.name());
        assertEquals("true", condition.havingValue());
        assertFalse(condition.matchIfMissing());
    }

    @Test
    void optionalOnnxEngineIsNotLoadedByTheStationRuntime() {
        ConditionalOnProperty condition = InferenceService.class
            .getAnnotation(ConditionalOnProperty.class);
        assertNotNull(condition);
        assertEquals("handwash.inference", condition.prefix());
        assertArrayEquals(new String[]{"api-enabled"}, condition.name());
        assertEquals("true", condition.havingValue());
    }

    @Test
    void enabledDiagnosticInferenceUsesOnlyTheLocalJavaOnnxEngine() {
        Fixture fixture = new Fixture();

        ResponseEntity<?> response = fixture.controller.infer(IMAGE, fixture.id, fixture.token);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("ONNX_LOCAL", responseMap(response).get("inferenceSource"));
        assertEquals(1, fixture.onnx.calls);
    }

    @Test
    void oversizedFileIsRejectedBeforeInvokingTheModel() {
        Fixture fixture = new Fixture();
        MockMultipartFile tooLarge = new MockMultipartFile(
            "file", "too-large.png", "image/png", new byte[(int) MAX_IMAGE_BYTES + 1]);

        ResponseEntity<?> response = fixture.controller.infer(tooLarge, fixture.id, fixture.token);

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertEquals("IMAGEN_EXCEDE_LIMITE", errorCode(response));
        assertEquals(0, fixture.onnx.calls);
    }

    @Test
    void malformedImageIsRejectedBeforeInvokingTheModel() {
        Fixture fixture = new Fixture();
        MockMultipartFile malformed = new MockMultipartFile(
            "file", "malformed.png", "image/png", new byte[]{1, 2, 3});

        ResponseEntity<?> response = fixture.controller.infer(malformed, fixture.id, fixture.token);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("IMAGEN_INVALIDA", errorCode(response));
        assertEquals(0, fixture.onnx.calls);
    }

    @Test
    void unavailableLocalOnnxReturnsServiceUnavailable() {
        Fixture fixture = new Fixture();
        fixture.onnx.unavailable = true;

        ResponseEntity<?> response = fixture.controller.infer(IMAGE, fixture.id, fixture.token);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("ONNX_INFERENCE_UNAVAILABLE", errorCode(response));
    }

    @Test
    void saturatedLocalOnnxReturnsTooManyRequests() {
        Fixture fixture = new Fixture();
        fixture.onnx.response = Map.of("error", "busy", "errorCode", "INFERENCE_BUSY");

        ResponseEntity<?> response = fixture.controller.infer(IMAGE, fixture.id, fixture.token);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("INFERENCIA_OCUPADA", errorCode(response));
    }

    @Test
    void unknownSessionIsRejectedBeforeInvokingTheModel() {
        Fixture fixture = new Fixture();

        ResponseEntity<?> response = fixture.controller.infer(IMAGE, "missing", null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(0, fixture.onnx.calls);
    }

    @Test
    void anonymousInferenceIsRejectedBeforeInvokingTheModel() {
        Fixture fixture = new Fixture();

        ResponseEntity<?> response = fixture.controller.infer(IMAGE, null, null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("SESSION_ID_REQUIRED", errorCode(response));
        assertEquals(0, fixture.onnx.calls);
    }

    @Test
    void existingSessionWithoutTokenIsRejectedBeforeInvokingTheModel() {
        Fixture fixture = new Fixture();
        String id = fixture.manager.crearSesion(TipoProtocolo.DOMESTICO);

        ResponseEntity<?> response = fixture.controller.infer(IMAGE, id, null);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals(0, fixture.onnx.calls);
    }

    @Test
    void localInferenceProjectsDomainStateToVersionedEvaluationDto() {
        Fixture fixture = new Fixture();
        fixture.onnx.response = Map.of("claseBackend", "Paso1_Palmas", "pasoConfianza", 0.95);

        ResponseEntity<?> response = fixture.controller.infer(IMAGE, fixture.id, fixture.token);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertInstanceOf(EvaluationResponse.class, responseMap(response).get("estadoSesion"));
        assertEquals("PROCESADA", responseMap(response).get("resultadoIngresoBackend"));
    }

    @Test
    void diagnosticInferenceRechecksDeviceCredentialAfterOnnxBeforeMutatingSession() {
        Fixture fixture = new Fixture();
        fixture.onnx.response = Map.of("claseBackend", "Paso1_Palmas", "pasoConfianza", 0.95);
        fixture.onnx.afterInference = () -> fixture.manager.emitirTokenDispositivo(fixture.id);

        ResponseEntity<?> response = fixture.controller.infer(
            IMAGE, fixture.id, null, fixture.deviceToken);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("ACCESO_NO_AUTORIZADO", errorCode(response));
        assertEquals(0, fixture.manager.getSesion(fixture.id).getIntentosReiniciados());
        assertNull(fixture.manager.getSesion(fixture.id).getEstadoActualResponse().getClaseCandidata());
    }

    @Test
    void diagnosticInferenceReportsMissingStrictV2EpochInsteadOfReturningModelSuccess() {
        SessionManager manager = new SessionManager(new Receptor());
        String id = manager.crearSesion(TipoProtocolo.DOMESTICO, true);
        String token = manager.getOwnerToken(id);
        ImagePayloadValidator validator = new ImagePayloadValidator(MAX_IMAGE_BYTES, MAX_IMAGE_PIXELS);
        StubInferenceService onnx = new StubInferenceService(validator, new InferenceAdmissionGate(1));
        onnx.response = Map.of("claseBackend", "Paso1_Palmas", "pasoConfianza", 0.95);
        InferenceController controller = new InferenceController(
            onnx, manager, new SessionTokenResolver(), new SessionResponseMapper(), validator);

        ResponseEntity<?> response = controller.infer(IMAGE, id, null, token);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("EVENTO_PRODUCTOR_RECHAZADO", errorCode(response));
        assertEquals(0, manager.getSesion(id).getIntentosReiniciados());
    }

    private static class Fixture {
        final ImagePayloadValidator imageValidator =
            new ImagePayloadValidator(MAX_IMAGE_BYTES, MAX_IMAGE_PIXELS);
        final StubInferenceService onnx = new StubInferenceService(
            imageValidator, new InferenceAdmissionGate(1));
        final SessionManager manager = new SessionManager(new Receptor());
        final String id = manager.crearSesion(TipoProtocolo.DOMESTICO);
        final String token = manager.getOwnerToken(id);
        final String deviceToken = manager.emitirTokenDispositivo(id).value();
        final InferenceController controller = new InferenceController(
            onnx, manager, new SessionTokenResolver(), new SessionResponseMapper(), imageValidator);
    }

    private static String errorCode(ResponseEntity<?> response) {
        return assertInstanceOf(ApiErrorResponse.class, response.getBody()).error();
    }

    private static Map<String, Object> responseMap(ResponseEntity<?> response) {
        return assertInstanceOf(InferenceResponse.class, response.getBody()).asMap();
    }

    private static class StubInferenceService extends InferenceService {
        int calls;
        boolean unavailable;
        Runnable afterInference = () -> {};
        Map<String, Object> response = Map.of("pasoDetectado", "Paso1_Palmas");

        StubInferenceService(ImagePayloadValidator validator, InferenceAdmissionGate gate) {
            super(validator, gate);
        }

        @Override
        public Map<String, Object> infer(byte[] imageBytes) {
            calls++;
            if (unavailable) return Map.of("error", "model missing");
            afterInference.run();
            return new LinkedHashMap<>(response);
        }
    }

    private static byte[] pngImage() {
        try {
            BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
