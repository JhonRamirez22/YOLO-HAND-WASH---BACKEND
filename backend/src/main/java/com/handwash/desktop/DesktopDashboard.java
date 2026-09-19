package com.handwash.desktop;

import com.handwash.model.WashStateResponse;
import com.handwash.model.Infraction;
import com.handwash.model.WhoAction;
import com.handwash.model.WashStep;
import com.handwash.model.Progress;
import com.handwash.model.WashSession;
import com.handwash.recognition.GeometryShadowSnapshot;
import com.handwash.service.SessionManager;
import com.handwash.service.ContinuityCameraLinkService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Connects the local Swing view directly to Java session state and camera MJPEG. */
@Component
@ConditionalOnProperty(name = "handwash.desktop.enabled", havingValue = "true")
public final class DesktopDashboard {
    private static final Logger log = LoggerFactory.getLogger(DesktopDashboard.class);
    private static final URI CAMERA_HEALTH_URI = URI.create("http://127.0.0.1:8091/health");

    private final SessionManager sessionManager;
    private final ObjectMapper objectMapper;
    private final ContinuityCameraLinkService cameraLink;
    private final HttpClient healthClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(1))
        .build();
    private final ScheduledExecutorService refreshers = Executors.newScheduledThreadPool(2, task -> {
        Thread thread = new Thread(task, "handwash-desktop-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean started = new AtomicBoolean();
    private final MjpegStreamReader streamReader;
    private volatile DesktopWindow window;
    private volatile String cameraSessionId;

    public DesktopDashboard(SessionManager sessionManager, ObjectMapper objectMapper,
                            ContinuityCameraLinkService cameraLink) {
        this.sessionManager = sessionManager;
        this.objectMapper = objectMapper;
        this.cameraLink = cameraLink;
        this.streamReader = new MjpegStreamReader(frame -> {
            DesktopWindow current = window;
            if (current != null) current.showFrame(frame);
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void openAfterBackendReady() {
        if (!started.compareAndSet(false, true)) return;
        if (GraphicsEnvironment.isHeadless()) {
            log.warn("Interfaz Java local omitida: el sistema no tiene entorno gráfico.");
            return;
        }
        SwingUtilities.invokeLater(() -> {
            DesktopWindow created = new DesktopWindow();
            created.setCameraLinkAction(() -> refreshers.execute(() -> {
                try {
                    refreshCamera();
                    var result = cameraSessionId == null
                        ? new ContinuityCameraLinkService.Result("unavailable", "El capturador está iniciando. Espera unos segundos.")
                        : cameraLink.link(cameraSessionId);
                    created.showCameraLinkResult(result.message());
                } catch (RuntimeException error) {
                    created.showCameraLinkResult("No se pudo enlazar el iPhone; consulta la terminal del launcher.");
                }
            }));
            window = created;
            created.setVisible(true);
            log.info("Ventana Java Swing local abierta.");
        });
        refreshers.scheduleWithFixedDelay(this::refreshSession, 0, 250, TimeUnit.MILLISECONDS);
        refreshers.scheduleWithFixedDelay(this::refreshCamera, 0, 750, TimeUnit.MILLISECONDS);
    }

    private void refreshSession() {
        try {
            List<WashSession> activeSessions = sessionManager.getSesionesActivas();
            WashSession session = cameraSessionId == null
                ? null : sessionManager.getSesion(cameraSessionId);
            if (session == null && activeSessions.size() == 1) {
                session = activeSessions.get(0);
            }
            if (session == null) {
                publishSession(null);
                return;
            }
            DesktopWindow current = window;
            if (current == null) return;
            synchronized (session) {
                if (sessionManager.getSesion(session.getSessionId()) != session) return;
                WashStateResponse response = session.getEstadoActualResponse();
                WashStep step = WashStep.fromClaseModelo(response.getEstadoActual());
                WhoAction omsAction = WhoAction.fromClaseModelo(response.getEstadoActual());
                Progress progress = response.getProgreso();
                Infraction lastIssue = response.getInfraccion();
                String issueText = lastIssue == null || lastIssue.getDetalle() == null
                    ? "" : lastIssue.getDetalle();
                String stepLabel = step == null || step == WashStep.FONDO
                    ? "Esperando inicio" : "Paso " + step.getNumero() + " · " + step.getNombre();
                if (step == null && response.getEstadoActual() != null) {
                    stepLabel = humanize(response.getEstadoActual());
                }
                if (omsAction != null) {
                    stepLabel = "Acción " + omsAction.getOrden() + " · " + omsAction.getNombre();
                }
                int currentStepNumber = step == null
                    ? (omsAction == null ? 0 : omsAction.getOrden()) : step.getNumero();
                boolean omsMode = "PROTOCOLO_OMS".equals(response.getModoEvaluacion());
                List<String> stepLabels = omsMode
                    ? WhoAction.SECUENCIA.stream().map(WhoAction::getNombre).toList()
                    : java.util.Arrays.stream(WashStep.values())
                        .filter(value -> value.getNumero() >= 1
                            && value.getNumero() <= WashStep.getMovimientosFriccionSoportados())
                        .map(value -> value.getNumero() + ". " + value.getNombre())
                        .toList();
                SessionView view = new SessionView(
                    session.getSessionId(), session.getProtocolo().value(),
                    session.getEstadoSesion().name(), stepLabel,
                    currentStepNumber,
                    progress == null ? 0 : progress.getPasosCompletados(),
                    progress == null ? WashStep.getMovimientosFriccionSoportados()
                        : progress.getPasosTotales(),
                    response.getModoEvaluacion(),
                    response.isSecuenciaFriccionObservadaCompleta(),
                    stepLabels,
                    response.getEstadoIntencion(), blankIfNull(response.getMotivoIntencion()),
                    response.getManosVisibles(), response.getTiempoTotalActivoMs(), issueText,
                    geometryShadowSummary(response.getGeometryShadow()));
                current.updateSession(view);
                var events = session.getDiagnosticResetEvents();
                if (events != null) current.updateResetEvents(events.toString());
            }
        } catch (RuntimeException error) {
            log.warn("No se pudo leer el estado local de la sesión: {}",
                error.getClass().getSimpleName());
        }
    }

    private void publishSession(SessionView view) {
        DesktopWindow current = window;
        if (current != null) current.updateSession(view);
    }

    private void refreshCamera() {
        try {
            HttpRequest request = HttpRequest.newBuilder(CAMERA_HEALTH_URI)
                .timeout(Duration.ofSeconds(1))
                .header("Accept", "application/json")
                .GET()
                .build();
            HttpResponse<String> response = healthClient.send(
                request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
            JsonNode health = objectMapper.readTree(response.body());
            String state = health.path("camera").asText("unavailable");
            String sessionId = health.path("sessionId").asText("");
            if (!sessionId.isBlank()) cameraSessionId = sessionId;
            JsonNode presence = health.path("handPresence");
            CameraView view = new CameraView(state,
                Math.max(0, presence.path("handsVisible").asInt()),
                Math.max(0, presence.path("warmupElapsedMs").asLong()),
                Math.max(0, presence.path("warmupRequiredMs").asLong()),
                presence.path("warmupComplete").asBoolean(false),
                presence.path("stepsEnabled").asBoolean(false));
            DesktopWindow current = window;
            if (current != null) {
                current.updateCamera(view);
                current.updateDiagnostics(diagnosticText(health.path("diagnostics")));
            }
            if ("connected".equals(state) && !sessionId.isBlank()
                && sessionManager.getSesion(sessionId) != null) {
                streamReader.connect(sessionId);
            } else {
                streamReader.disconnect();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException error) {
            streamReader.disconnect();
            DesktopWindow current = window;
            if (current != null) {
                current.updateCamera(CameraView.unavailable());
                current.updateDiagnostics("Diagnóstico: capturador no disponible");
            }
        }
    }

    static String diagnosticText(JsonNode diagnostic) {
        if (diagnostic.isMissingNode() || diagnostic.isNull()) return "Diagnóstico: esperando observaciones";
        JsonNode fps = diagnostic.path("fps");
        String poseRate = fps.has("posePrimary")
            ? "\nPose: " + String.format(java.util.Locale.ROOT, "%.1f", fps.path("posePrimary").asDouble())
                + " inferencias primarias/s · " + String.format(java.util.Locale.ROOT, "%.1f", fps.path("pose").asDouble())
                + " llamadas/s (incluye recuperación)"
            : "";
        return "Pose #" + diagnostic.path("poseSequence").asLong(-1)
            + " · estrictas " + diagnostic.path("strictHands").asInt(0)
            + " · kp " + diagnostic.path("keypointCounts")
            + "\nMovimiento válido: " + diagnostic.path("movementValid").asBoolean(false)
            + " · residual " + diagnostic.path("normalizedMovement").asDouble(0)
            + "\nCandidato: " + diagnostic.path("candidate").asText("sin candidato")
            + " · confianza " + diagnostic.path("candidateConfidence").asText("—")
            + "\nPalmas (cuadro): " + diagnostic.path("classifierScores").path("01_palm_to_palm").asText("—")
            + " · ROI solo diagnóstico: " + diagnostic.path("classifierRoiShadowScores").path("01_palm_to_palm").asText("—")
            + "\nBloqueos observados: " + diagnostic.path("reasons")
            + "\nContadores por frame de pose (NO reinicios Java): " + diagnostic.path("counts")
            + poseRate
            + atulShadowText(diagnostic.path("atulShadow"));
    }

    private static String atulShadowText(JsonNode shadow) {
        if (shadow.isMissingNode() || shadow.isNull()) return "";
        return "\nAtul SOLO COMPARACIÓN, etiquetas sin validar: " + shadow.path("status").asText()
            + " · fresco " + shadow.path("fresh").asBoolean(false)
            + " · frame #" + shadow.path("sequence").asLong(-1)
            + " · ms " + shadow.path("latencyMs").asText("—")
            + "\nCuadro: " + shadow.path("full") + "\nROI: " + shadow.path("roi");
    }

    private static String blankIfNull(String value) {
        return value == null ? "" : value;
    }

    private static String geometryShadowSummary(GeometryShadowSnapshot shadow) {
        if (shadow == null) return "apagado";
        String candidate = shadow.proposedMovement() == null ? "sin candidato"
            : shadow.proposedMovement();
        return String.format(java.util.Locale.ROOT,
            "%s · %s · evidencia %.2f · %d obs · %s · nunca acredita",
            shadow.status(), candidate, shadow.evidenceScore(), shadow.validObservations(),
            shadow.producerAgreement());
    }

    private static String humanize(String value) {
        return value.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
    }

    @PreDestroy
    public void close() {
        refreshers.shutdownNow();
        streamReader.close();
        DesktopWindow current = window;
        if (current != null) SwingUtilities.invokeLater(current::dispose);
    }

    record SessionView(String sessionId, String protocol, String status, String stepLabel,
                       int currentStepNumber, int stepsCompleted, int stepsTotal,
                       String evaluationMode, boolean frictionSequenceObservedComplete,
                       List<String> stepLabels, String intent, String reason,
                       int handsVisible, long activeMs, String issue, String geometryShadow) {}

    record CameraView(String state, int handsVisible, long warmupElapsedMs,
                      long warmupRequiredMs, boolean warmupComplete, boolean stepsEnabled) {
        static CameraView unavailable() {
            return new CameraView("unavailable", 0, 0, 0, false, false);
        }
    }
}
