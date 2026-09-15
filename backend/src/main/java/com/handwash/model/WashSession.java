package com.handwash.model;

import com.handwash.state.*;
import com.handwash.strategy.ValidationRuleStrategy;
import com.handwash.strategy.ValidationRuleStrategyFactory;
import com.handwash.intention.WashIntentionChain;
import com.handwash.intention.IntentionTracker;
import com.handwash.recognition.GeometryShadowSnapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;

public class WashSession {
    public enum MotivoExpiracion { IDLE_TIMEOUT, PIPELINE_FAILURE }
    private MotivoExpiracion motivoExpiracion;
    public record DiagnosticResetEvent(long sequence, long epochMillis, String reason,
                                       String previousState, String nextState) {}
    private boolean phaseOneDiagnostics;
    private long diagnosticResetSequence;
    private final List<DiagnosticResetEvent> diagnosticResetEvents = new ArrayList<>();

    public synchronized void setPhaseOneDiagnostics(boolean enabled) {
        phaseOneDiagnostics = enabled;
    }

    public synchronized List<DiagnosticResetEvent> getDiagnosticResetEvents() {
        return phaseOneDiagnostics ? List.copyOf(diagnosticResetEvents) : null;
    }

    private void recordDiagnosticReset(String reason, String previousState) {
        if (!phaseOneDiagnostics) return;
        var event = new DiagnosticResetEvent(++diagnosticResetSequence, System.currentTimeMillis(),
            reason, previousState, getEstadoActualResponse().getEstadoActual());
        if (diagnosticResetEvents.size() >= 256) diagnosticResetEvents.remove(0);
        diagnosticResetEvents.add(event);
        org.slf4j.LoggerFactory.getLogger(WashSession.class).info(
            "DIAGNOSTIC_RESET sequence={} timestamp={} reason={} before={} after={}",
            event.sequence(), event.epochMillis(), event.reason(), event.previousState(), event.nextState());
    }
    private final String sessionId;
    private final ProtocolType protocolo;
    private final ValidationRuleStrategy estrategia;
    private final long maxGapDeteccionMs;
    private final long maxGapTransicionMs;
    private final WhoSession sesionOms;
    private final IntentionTracker intencion = new IntentionTracker();
    private final BalancedFrictionAttempt balanced;
    private String modoEvaluacion;
    private boolean secuenciaFriccionObservadaCompleta;
    private WashStepState estadoActual;
    private SessionStatus estadoSesion;
    private final long inicioTimestamp;
    private final long inicioMonotonicNanos;
    private int pasosCompletados;
    /** Último error del intento; se conserva para que el WebSocket coalescido no lo pierda. */
    private Infraction infraccionActual;
    private Infraction ultimoErrorReinicio;
    private final List<Infraction> historialInfracciones = new ArrayList<>();
    private final List<Infraction> infraccionesIntentoActual = new ArrayList<>();
    private final List<WashAttemptSummary> intentosAnteriores = new ArrayList<>();
    private static final int MAX_HISTORIAL_INFRACCIONES = 500;
    private static final int MAX_INFRACCIONES_POR_INTENTO = 50;
    private static final int MAX_INTENTOS_GUARDADOS = 100;
    private final java.util.function.LongSupplier monotonicNanos;
    private long ultimaInfraccionNanos;
    private int intentoUltimaInfraccion = -1;
    private long infraccionesOmitidas;
    private final Map<WashStep, Long> tiempoPorPasoMs = new EnumMap<>(WashStep.class);
    private GeometryShadowSnapshot geometryShadow;
    private com.handwash.recognition.ShadowSequenceSnapshot sequenceDecoderShadow;

    private WashStep pasoPendienteValidacion;
    private long tiempoPendienteValidacionMs;
    /** A shift session is kept by its shift, not by activity: an idle sink must not end it. */
    private boolean sesionDeTurno;
    private volatile long lastProducerPulseEpochMs;
    private long ultimaActividadTimestamp;
    private long ultimaActividadMonotonicNanos;
    private long finTimestamp;
    private long finMonotonicNanos;
    private long ultimoTimestampDeteccion;
    private WashStep ultimoPasoDetectado;
    private double sumaConfianzas;
    private long cantidadDetecciones;
    private float confianzaDeteccionActual;
    private String claseCandidata;
    private float confianzaCandidata;
    private long umbralConfirmacionIntencionMs;
    private int numeroIntentoActual;
    private int intentosReiniciados;
    private long inicioIntentoMonotonicNanos;
    private long tiempoIntencionConfirmadaMs;
    private WashStep pasoCandidatoPendiente;
    private int observacionesPasoCandidato;
    private long ultimaObservacionPasoCandidato;
    private long inicioPasoCandidatoTimestamp;
    private long tiempoPasoCandidatoConfirmadoMs;
    private static final int OBSERVACIONES_PARA_CONFIRMAR_CAMBIO = 2;
    private static final long DEFAULT_MAX_GAP_DETECCION_MS = 1500L;
    private static final long DEFAULT_MAX_GAP_TRANSICION_MS = 650L;

    public WashSession(String sessionId, ProtocolType protocolo) {
        this(sessionId, protocolo, new ValidationRuleStrategyFactory().crear(protocolo));
    }

    public WashSession(String sessionId, ProtocolType protocolo, ValidationRuleStrategy estrategia) {
        this(sessionId, protocolo, estrategia, DEFAULT_MAX_GAP_DETECCION_MS);
    }

    public WashSession(String sessionId, ProtocolType protocolo,
                        ValidationRuleStrategy estrategia, long maxGapDeteccionMs) {
        this(sessionId, protocolo, estrategia, maxGapDeteccionMs,
            DEFAULT_MAX_GAP_TRANSICION_MS, false);
    }

    public WashSession(String sessionId, ProtocolType protocolo,
                        ValidationRuleStrategy estrategia, long maxGapDeteccionMs,
                        boolean omsModelReady) {
        this(sessionId, protocolo, estrategia, maxGapDeteccionMs,
            DEFAULT_MAX_GAP_TRANSICION_MS, omsModelReady);
    }

    public WashSession(String sessionId, ProtocolType protocolo,
                        ValidationRuleStrategy estrategia, long maxGapDeteccionMs,
                        long maxGapTransicionMs, boolean omsModelReady) {
        this(sessionId, protocolo, estrategia, maxGapDeteccionMs, maxGapTransicionMs, omsModelReady,
            System::nanoTime);
    }

    /** The monotonic clock is injectable so durations and windows can be tested without sleeping. */
    WashSession(String sessionId, ProtocolType protocolo,
                 ValidationRuleStrategy estrategia, long maxGapDeteccionMs,
                 long maxGapTransicionMs, boolean omsModelReady,
                 java.util.function.LongSupplier monotonicNanos) {
        this.monotonicNanos = java.util.Objects.requireNonNull(monotonicNanos);
        this.sessionId = java.util.Objects.requireNonNull(sessionId, "sessionId");
        this.protocolo = java.util.Objects.requireNonNull(protocolo, "protocolo");
        this.estrategia = java.util.Objects.requireNonNull(estrategia, "estrategia");
        if (maxGapDeteccionMs < 100L || maxGapDeteccionMs > 5_000L) {
            throw new IllegalArgumentException("maxGapDeteccionMs debe estar entre 100 y 5000");
        }
        if (maxGapTransicionMs < 100L || maxGapTransicionMs > 1_500L) {
            throw new IllegalArgumentException("maxGapTransicionMs debe estar entre 100 y 1500");
        }
        this.maxGapDeteccionMs = maxGapDeteccionMs;
        this.maxGapTransicionMs = maxGapTransicionMs;
        this.balanced = com.handwash.strategy.BalancedFrictionStrategy.VERSION.equals(estrategia.getVersionPolitica())
            ? new BalancedFrictionAttempt(sessionId, estrategia, maxGapDeteccionMs, maxGapTransicionMs) : null;
        if (balanced != null) modoEvaluacion = "FRICCION_PARCIAL";
        this.sesionOms = new WhoSession(
            sessionId, omsModelReady, maxGapDeteccionMs, estrategia.getDuracionTotalMs(),
            estrategia.getDuracionMinimaFaseOmsMs());
        long startedAtEpochMs = System.currentTimeMillis();
        long startedAtMonotonicNanos = monotonicNanos.getAsLong();
        this.inicioTimestamp = startedAtEpochMs;
        this.inicioMonotonicNanos = startedAtMonotonicNanos;
        this.ultimaActividadTimestamp = this.inicioTimestamp;
        this.lastProducerPulseEpochMs = this.inicioTimestamp;
        this.ultimaActividadMonotonicNanos = startedAtMonotonicNanos;
        this.estadoActual = new WaitingToStartState();
        this.estadoSesion = SessionStatus.ESPERANDO_INICIO;
        this.pasosCompletados = 0;
    }

    /** Se ejecuta dentro del lock de sesión antes de State; rechazar nunca suma tiempo. */
    public synchronized boolean evaluarIntencion(DetectionEvent evento, WashIntentionChain cadena) {
        tiempoIntencionConfirmadaMs = 0L;
        intencion.limpiarUltimoCodigoRechazo();
        // Una observación vieja no puede pausar, reiniciar ni acreditar un
        // intento más nuevo, incluso cuando su etiqueta sea Fondo.
        if (intencion.esRepetida(evento)) return false;
        if (balanced != null) balanced.refreshRevision();
        WashStep pasoCandidato = evento.getPasoLavadoResuelto();
        claseCandidata = pasoCandidato == null
            ? evento.getClaseDetectada() : pasoCandidato.name();
        confianzaCandidata = evento.getConfianza();
        umbralConfirmacionIntencionMs = cadena.confirmacionMs();
        boolean activo = estadoSesion == SessionStatus.EN_PROGRESO;
        if (balanced == null && activo && intencion.debeReiniciar(evento.getServerReceivedAtMonotonicMs(), cadena)) {
            procesarDeteccionSinRespuesta(WashStep.FONDO,
                evento.getServerReceivedAtMonotonicMs(), 1.0f);
            activo = false;
        }
        if (activo && intencion.tieneHueco(
            evento.getServerReceivedAtMonotonicMs(), maxGapDeteccionMs)) {
            limpiarCandidatoSecuencia();
            ultimoTimestampDeteccion = 0;
            ultimoPasoDetectado = null;
        }
        boolean permitido = intencion.evaluar(evento, activo, cadena);
        if (permitido && !activo) {
            // Esa ventana solo contiene observaciones de palmas que superaron
            // todos los filtros de inicio; no debe volver a perderse al abrir Paso 1.
            tiempoIntencionConfirmadaMs = intencion.tiempoCandidatoMs();
        } else if (!permitido) {
            tiempoIntencionConfirmadaMs = 0L;
        }
        if (!permitido) {
            if (balanced != null) balanced.pause("INSUFFICIENT_EVIDENCE");
            limpiarCandidatoSecuencia();
            ultimoTimestampDeteccion = 0;
            ultimoPasoDetectado = null;
            boolean sinEvidencia = evento.getPasoLavadoResuelto() == WashStep.FONDO;
            if (balanced == null && activo && (sinEvidencia || intencion.debeReiniciar(evento.getServerReceivedAtMonotonicMs(), cadena))) {
                procesarDeteccionSinRespuesta(WashStep.FONDO,
                    evento.getServerReceivedAtMonotonicMs(), 1.0f);
            }
        }
        return permitido;
    }

    /** Rejected fresh observations break transition votes and active dwell timing. */
    public synchronized void descartarDeteccion(DetectionEvent evento) {
        descartarDeteccion(evento, null);
    }

    /** Rejected input can provide a precise dashboard reason without changing session rules. */
    public synchronized void descartarDeteccion(DetectionEvent evento, String motivo) {
        tiempoIntencionConfirmadaMs = 0L;
        if ("PROTOCOLO_OMS".equals(modoEvaluacion)) {
            sesionOms.descartarObservacion(motivo);
            return;
        }
        if (intencion.esRepetida(evento)) return;
        if (balanced != null) balanced.refreshRevision();
        if (balanced != null) balanced.pause("INSUFFICIENT_EVIDENCE");
        boolean activo = estadoSesion == SessionStatus.EN_PROGRESO;
        if (motivo == null) intencion.descartar(evento, activo);
        else intencion.descartar(evento, activo, motivo);
        limpiarCandidatoSecuencia();
        // Do not credit the gap spanning a rejected frame to the previous phase.
        ultimoTimestampDeteccion = 0L;
        ultimoPasoDetectado = null;
    }

    public synchronized WashStateResponse procesarDeteccion(WashStep claseDetectada, long timestampMs) {
        return procesarDeteccion(claseDetectada, timestampMs, 1.0f);
    }

    public synchronized WashStateResponse procesarDeteccion(
        WashStep claseDetectada, long timestampMs, float confianza
    ) {
        return procesarDeteccionInterno(claseDetectada, timestampMs, confianza, true);
    }

    /** Processes a live frame without allocating a response DTO that the camera pipeline discards. */
    public synchronized void procesarDeteccionSinRespuesta(
        WashStep claseDetectada, long timestampMs, float confianza
    ) {
        procesarDeteccionInterno(claseDetectada, timestampMs, confianza, false);
    }

    private WashStateResponse procesarDeteccionInterno(
        WashStep claseDetectada, long timestampMs, float confianza, boolean incluirRespuesta
    ) {
        if (modoEvaluacion == null) modoEvaluacion = "FRICCION_PARCIAL";
        if (!"FRICCION_PARCIAL".equals(modoEvaluacion)) {
            registrarInfraccion(InfractionType.MODO_DETECCION_INCOMPATIBLE,
                "Esta sesión ya está evaluando el protocolo OMS completo", claseDetectada);
            return crearRespuestaSiSolicitada(incluirRespuesta);
        }
        if (estadoSesion == SessionStatus.COMPLETADA || estadoSesion == SessionStatus.EXPIRADA) {
            return crearRespuestaSiSolicitada(incluirRespuesta);
        }

        if (claseDetectada == null) {
            return crearRespuestaSiSolicitada(incluirRespuesta);
        }
        // Paso 7 is not one of the six supported friction movements. Keep this
        // guard in the domain model as defense in depth for non-HTTP callers.
        if (claseDetectada == WashStep.PASO_7_CIRCULARES) {
            return crearRespuestaSiSolicitada(incluirRespuesta);
        }

        if (balanced != null) {
            if (claseDetectada == WashStep.FONDO) balanced.pause("NO_RELIABLE_STEP");
            else balanced.observe(claseDetectada, timestampMs, tiempoIntencionConfirmadaMs, confianza);
            tiempoIntencionConfirmadaMs = 0;
            confianzaDeteccionActual = confianza;
            syncBalancedState();
            marcarActividad();
            return crearRespuestaSiSolicitada(incluirRespuesta);
        }

        long eventoTimestamp = timestampMs > 0 ? timestampMs : System.currentTimeMillis();
        if (ultimoTimestampDeteccion > 0 && eventoTimestamp < ultimoTimestampDeteccion) {
            // Un frame retrasado no debe adelantar el reloj ni alterar la secuencia.
            return crearRespuestaSiSolicitada(incluirRespuesta);
        }
        WashStepState estadoAnterior = estadoActual;
        WashStep pasoAnterior = estadoAnterior.getPasoActual();
        if (claseDetectada == WashStep.FONDO) {
            // El capturador solo envía Fondo tras una pérdida sostenida de
            // evidencia. Reinicia el intento porque no se puede verificar lo
            // ocurrido mientras las manos estuvieron fuera de cámara.
            if (pasoAnterior != null && numeroIntentoActual > 0) {
                long tiempoObservadoMs = getTiempoPaso(estadoAnterior);
                if (!estrategia.validarTiempoPaso(pasoAnterior, tiempoObservadoMs)) {
                    registrarInfraccion(InfractionType.TIEMPO_INSUFICIENTE,
                        String.format("Paso %s: %.1fs observados / %.1fs requeridos antes de perder evidencia",
                            pasoAnterior.getNombre(),
                            tiempoObservadoMs / 1000.0,
                            estrategia.getTiempoRequeridoPaso(pasoAnterior) / 1000.0),
                        pasoAnterior);
                }
                registrarInfraccion(InfractionType.EVIDENCIA_VISUAL_INTERRUPTA,
                    "Se perdió la observación de las manos; vuelva a iniciar desde PASO_1_PALMAS",
                    pasoAnterior);
                reiniciarIntento("EVIDENCIA_VISUAL_INTERRUPTA");
            }
            return crearRespuestaSiSolicitada(incluirRespuesta);
        }

        boolean candidatoTransicionValida = pasoAnterior != null
            && claseDetectada != pasoAnterior
            && claseDetectada == pasoAnterior.siguiente();
        long tiempoFaseNuevaConfirmadaMs = 0L;
        if (candidatoTransicionValida) {
            long intervaloDesdePasoActual = eventoTimestamp - ultimoTimestampDeteccion;
            // El cierre usa la tolerancia de muestreo; el par candidato tiene
            // su propia ventana más estricta dentro de confirmarCandidatoPaso.
            boolean cierreContinuo = ultimoPasoDetectado == pasoAnterior
                && ultimoTimestampDeteccion > 0 && intervaloDesdePasoActual >= 0
                && intervaloDesdePasoActual <= maxGapDeteccionMs;
            // Cierra el tiempo del paso actual en la primera observación del
            // siguiente; la fase nueva solo cambia cuando otra observación
            // distinta confirma la misma clase. No se exige amplitud ni una
            // trayectoria concreta de las manos.
            if (cierreContinuo) {
                actualizarTiempoActivo(claseDetectada, eventoTimestamp);
            } else {
                // No rellenar el hueco de una pausa con tiempo acreditable del
                // paso anterior, aunque la ventana general de sesión sea mayor.
                ultimoTimestampDeteccion = eventoTimestamp;
                ultimoPasoDetectado = claseDetectada;
            }
            if (!confirmarCandidatoPaso(claseDetectada, eventoTimestamp)) {
                marcarActividad();
                return crearRespuestaSiSolicitada(incluirRespuesta);
            }
            // Tras dos observaciones consistentes, el intervalo de confirmación
            // pertenece al nuevo paso, no al paso que ya se cerró al primer candidato.
            tiempoFaseNuevaConfirmadaMs = tiempoPasoCandidatoConfirmadoMs;
        }

        if (pasoAnterior == null && claseDetectada != WashStep.PASO_1_PALMAS) {
            registrarInfraccion(InfractionType.PASO_OMITIDO,
                detalleReinicioPorSecuencia(claseDetectada), claseDetectada);
            return crearRespuestaSiSolicitada(incluirRespuesta);
        }
        if (pasoAnterior != null && claseDetectada != pasoAnterior
            && claseDetectada != pasoAnterior.siguiente()) {
            if (!confirmarCandidatoPaso(claseDetectada, eventoTimestamp)) {
                return crearRespuestaSiSolicitada(incluirRespuesta);
            }
            limpiarCandidatoSecuencia();
            if (pasoAnterior == WashStep.PASO_6_PUNTA_DE_DEDOS
                && !estrategia.validarTiempoPaso(pasoAnterior, getTiempoPaso(estadoAnterior))) {
                long tiempoPasoFinalMs = getTiempoPaso(estadoAnterior);
                registrarInfraccion(InfractionType.TIEMPO_INSUFICIENTE,
                    String.format("Paso %s: %.1fs / %.1fs requeridos; vuelva a PASO_1_PALMAS",
                        pasoAnterior.getNombre(),
                        tiempoPasoFinalMs / 1000.0,
                        estrategia.getTiempoRequeridoPaso(pasoAnterior) / 1000.0),
                    pasoAnterior);
                reiniciarIntento("TIEMPO_INSUFICIENTE_PASO_6_PUNTA_DE_DEDOS");
                return crearRespuestaSiSolicitada(incluirRespuesta);
            }
            InfractionType tipo = claseDetectada.getNumero() > pasoAnterior.getNumero()
                ? InfractionType.PASO_OMITIDO : InfractionType.PASO_INVALIDO;
            registrarInfraccion(tipo, detalleReinicioPorSecuencia(claseDetectada), claseDetectada);
            reiniciarIntento("PASO_FUERA_DE_SECUENCIA");
            return crearRespuestaSiSolicitada(incluirRespuesta);
        }
        limpiarCandidatoSecuencia();

        if (!candidatoTransicionValida) {
            actualizarTiempoActivo(claseDetectada, eventoTimestamp);
        }
        marcarActividad();
        if (Float.isFinite(confianza) && confianza >= 0.0f && confianza <= 1.0f) {
            sumaConfianzas += confianza;
            cantidadDetecciones++;
            confianzaDeteccionActual = confianza;
        }

        estadoActual = estadoActual.procesarDeteccion(claseDetectada);
        WashStep pasoNuevo = estadoActual.getPasoActual();
        if (pasoAnterior == null && pasoNuevo == WashStep.PASO_1_PALMAS) {
            iniciarIntento(eventoTimestamp);
        }
        if (pasoNuevo != null && pasoNuevo != pasoAnterior) {
            if (tiempoFaseNuevaConfirmadaMs > 0L) {
                tiempoPorPasoMs.merge(pasoNuevo, tiempoFaseNuevaConfirmadaMs, Long::sum);
            }
            ultimoPasoDetectado = pasoNuevo;
            ultimoTimestampDeteccion = eventoTimestamp;
        }

        // Six friction movements are informational progress only. This does
        // not terminate the session or imply a completed OMS hand-wash.
        if (!secuenciaFriccionObservadaCompleta
            && claseDetectada == WashStep.PASO_6_PUNTA_DE_DEDOS
            && pasoNuevo == WashStep.PASO_6_PUNTA_DE_DEDOS
            && estrategia.validarTiempoPaso(pasoNuevo, getTiempoPasoActual())) {
            tiempoPorPasoMs.put(pasoNuevo, getTiempoPasoActual());
            pasosCompletados = WashStep.getMovimientosFriccionSoportados();
            pasoPendienteValidacion = pasoNuevo;
            tiempoPendienteValidacionMs = getTiempoPasoActual();
            secuenciaFriccionObservadaCompleta = true;
        }

        if (estadoSesion == SessionStatus.ESPERANDO_INICIO && estadoActual.getPasoActual() != null) {
            estadoSesion = SessionStatus.EN_PROGRESO;
        }

        boolean huboTransicion = (pasoAnterior != null && pasoNuevo != null && pasoNuevo != pasoAnterior)
            || (pasoAnterior != null && estadoActual.isCompletado());

        if (huboTransicion && pasoAnterior != null) {
            long tiempoPasoAnterior = tiempoPorPasoMs.getOrDefault(pasoAnterior, getTiempoPaso(estadoAnterior));
            tiempoPorPasoMs.put(pasoAnterior, tiempoPasoAnterior);
            pasoPendienteValidacion = pasoAnterior;
            tiempoPendienteValidacionMs = tiempoPasoAnterior;
        }

        if (estadoActual.isCompletado()) {
            estadoSesion = SessionStatus.COMPLETADA;
            pasosCompletados = 7;
            finTimestamp = System.currentTimeMillis();
            finMonotonicNanos = monotonicNanos.getAsLong();
        } else if (pasoNuevo != null) {
            pasosCompletados = Math.max(pasosCompletados, pasoNuevo.getNumero() - 1);
        }

        return crearRespuestaSiSolicitada(incluirRespuesta);
    }

    private WashStateResponse crearRespuestaSiSolicitada(boolean incluirRespuesta) {
        return incluirRespuesta ? getEstadoActualResponse() : null;
    }

    public synchronized WashStateResponse procesarAccionOms(
        WhoAction accion, Map<String, SoapEvidence> evidencia, long timestampMs, float confianza
    ) {
        if (modoEvaluacion == null) modoEvaluacion = "PROTOCOLO_OMS";
        if (!"PROTOCOLO_OMS".equals(modoEvaluacion)) {
            registrarInfraccion(InfractionType.MODO_DETECCION_INCOMPATIBLE,
                "Esta sesión ya está evaluando únicamente movimientos de fricción", null);
            return buildResponse();
        }
        return sesionOms.procesar(accion, evidencia, timestampMs, confianza);
    }

    private void actualizarTiempoActivo(WashStep claseDetectada, long eventoTimestamp) {
        WashStep pasoActual = estadoActual.getPasoActual();
        boolean sigueEnElPaso = pasoActual != null && claseDetectada == pasoActual;
        boolean transicionValida = pasoActual != null && claseDetectada == pasoActual.siguiente();
        if (pasoActual != null && (sigueEnElPaso || transicionValida)
            && ultimoPasoDetectado == pasoActual && ultimoTimestampDeteccion > 0) {
            long delta = eventoTimestamp - ultimoTimestampDeteccion;
            if (delta >= 0 && delta <= maxGapDeteccionMs) {
                // La detección del siguiente paso cierra el intervalo observado
                // del paso anterior; no se debe perder ese último tramo.
                tiempoPorPasoMs.merge(pasoActual, delta, Long::sum);
            }
        }
        ultimoTimestampDeteccion = eventoTimestamp;
        ultimoPasoDetectado = claseDetectada;
    }

    /** Confirms a phase change after two distinct observations within the allowed gap. */
    private boolean confirmarCandidatoPaso(WashStep detectado, long eventoTimestamp) {
        long intervalo = eventoTimestamp - ultimaObservacionPasoCandidato;
        boolean mismoCandidatoDentroDeVentana = pasoCandidatoPendiente == detectado
            && intervalo > 0 && intervalo <= maxGapTransicionMs;
        if (!mismoCandidatoDentroDeVentana) {
            pasoCandidatoPendiente = detectado;
            observacionesPasoCandidato = 1;
            inicioPasoCandidatoTimestamp = eventoTimestamp;
            tiempoPasoCandidatoConfirmadoMs = 0L;
        } else {
            observacionesPasoCandidato++;
            WashStep pasoActual = estadoActual.getPasoActual();
            if (observacionesPasoCandidato >= OBSERVACIONES_PARA_CONFIRMAR_CAMBIO
                && pasoActual != null && detectado == pasoActual.siguiente()) {
                // Se acredita retrospectivamente al paso nuevo solo tras
                // corroborar que la primera observación no fue un falso cambio.
                tiempoPasoCandidatoConfirmadoMs = Math.max(
                    0L, eventoTimestamp - inicioPasoCandidatoTimestamp);
            }
        }
        ultimaObservacionPasoCandidato = eventoTimestamp;
        // An unconfirmed candidate breaks the current dwell interval without
        // changing State or erasing the bounded attempt history.
        ultimoPasoDetectado = null;
        return observacionesPasoCandidato >= OBSERVACIONES_PARA_CONFIRMAR_CAMBIO;
    }

    private void limpiarCandidatoSecuencia() {
        pasoCandidatoPendiente = null;
        observacionesPasoCandidato = 0;
        ultimaObservacionPasoCandidato = 0L;
        inicioPasoCandidatoTimestamp = 0L;
        tiempoPasoCandidatoConfirmadoMs = 0L;
    }

    public synchronized void registrarInfraccion(InfractionType tipo, String detalle, WashStep paso) {
        if ("PROTOCOLO_OMS".equals(modoEvaluacion)) {
            sesionOms.registrarError(tipo, detalle, paso == null ? null : paso.name());
            return;
        }
        long ahora = System.currentTimeMillis();
        Infraction infraccion = new Infraction(
            tipo,
            detalle,
            paso != null ? paso.name() : null,
            Instant.ofEpochMilli(ahora).toString()
        );
        Infraction ultima = historialInfracciones.isEmpty() ? null
            : historialInfracciones.get(historialInfracciones.size() - 1);
        if (intentoUltimaInfraccion == numeroIntentoActual
            && ultima != null && ultima.getTipo() == tipo
            && java.util.Objects.equals(ultima.getPaso(), infraccion.getPaso())
            && java.util.Objects.equals(ultima.getDetalle(), detalle)
            && monotonicNanos.getAsLong() - ultimaInfraccionNanos < TimeUnit.MILLISECONDS.toNanos(2_000L)) {
            this.infraccionActual = ultima;
            return;
        }
        this.infraccionActual = infraccion;
        agregarAlHistorial(infraccion);
    }

    private void agregarAlHistorial(Infraction infraccion) {
        if (historialInfracciones.size() >= MAX_HISTORIAL_INFRACCIONES) {
            historialInfracciones.remove(0);
            infraccionesOmitidas++;
        }
        historialInfracciones.add(infraccion);
        if (infraccionesIntentoActual.size() >= MAX_INFRACCIONES_POR_INTENTO) {
            infraccionesIntentoActual.remove(0);
        }
        infraccionesIntentoActual.add(infraccion);
        ultimaInfraccionNanos = monotonicNanos.getAsLong();
        intentoUltimaInfraccion = numeroIntentoActual;
    }

    private void iniciarIntento(long eventoTimestamp) {
        numeroIntentoActual++;
        limpiarCandidatoSecuencia();
        long tiempoPalmasConfirmadasMs = tiempoIntencionConfirmadaMs;
        tiempoIntencionConfirmadaMs = 0L;
        inicioIntentoMonotonicNanos = monotonicNanos.getAsLong()
            - TimeUnit.MILLISECONDS.toNanos(tiempoPalmasConfirmadasMs);
        infraccionActual = null;
        infraccionesIntentoActual.clear();
        tiempoPorPasoMs.clear();
        if (tiempoPalmasConfirmadasMs > 0L) {
            tiempoPorPasoMs.put(WashStep.PASO_1_PALMAS, tiempoPalmasConfirmadasMs);
        }
        ultimoTimestampDeteccion = eventoTimestamp;
        ultimoPasoDetectado = WashStep.PASO_1_PALMAS;
        pasosCompletados = 0;
        secuenciaFriccionObservadaCompleta = false;
        estadoSesion = SessionStatus.EN_PROGRESO;
    }

    private String detalleReinicioPorSecuencia(WashStep detectado) {
        return "Secuencia incorrecta: se detectó " + detectado.name()
            + "; vuelva a iniciar desde PASO_1_PALMAS";
    }

    /**
     * Cierra y conserva el intento fallido, y vuelve la State machine al paso inicial.
     * El historial de sesión no se borra, pero los tiempos de un intento no se
     * mezclan con los del siguiente.
     */
    public synchronized void reiniciarIntento(String motivo) {
        String diagnosticPreviousState = phaseOneDiagnostics
            ? getEstadoActualResponse().getEstadoActual() : null;
        intencion.reiniciar();
        limpiarCandidatoSecuencia();
        if ("PROTOCOLO_OMS".equals(modoEvaluacion)) {
            sesionOms.reiniciarIntento(motivo);
            recordDiagnosticReset(motivo, diagnosticPreviousState);
            return;
        }
        if (estadoSesion == SessionStatus.EN_PROGRESO && numeroIntentoActual > 0) {
            ultimoErrorReinicio = infraccionActual;
            Map<String, Long> times = new LinkedHashMap<>();
            tiempoPorPasoMs.forEach((paso, milliseconds) -> times.put(paso.name(), milliseconds));
            WashAttemptSummary resumen = new WashAttemptSummary(
                numeroIntentoActual,
                "REINICIADO",
                motivo,
                duracionIntentoMonotonicaMs(),
                times,
                List.copyOf(infraccionesIntentoActual)
            );
            if (intentosAnteriores.size() >= MAX_INTENTOS_GUARDADOS) {
                intentosAnteriores.remove(0);
            }
            intentosAnteriores.add(resumen);
            intentosReiniciados++;
        }
        estadoActual = new WaitingToStartState();
        estadoSesion = SessionStatus.ESPERANDO_INICIO;
        pasosCompletados = 0;
        secuenciaFriccionObservadaCompleta = false;
        tiempoPorPasoMs.clear();
        pasoPendienteValidacion = null;
        tiempoPendienteValidacionMs = 0L;
        ultimoTimestampDeteccion = 0L;
        ultimoPasoDetectado = null;
        inicioIntentoMonotonicNanos = 0L;
        tiempoIntencionConfirmadaMs = 0L;
        infraccionesIntentoActual.clear();
        recordDiagnosticReset(motivo, diagnosticPreviousState);
    }

    private long duracionIntentoMonotonicaMs() {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(
            monotonicNanos.getAsLong() - inicioIntentoMonotonicNanos));
    }

    public synchronized WashStateResponse getEstadoActualResponse() {
        if ("PROTOCOLO_OMS".equals(modoEvaluacion)) return sesionOms.getEstadoActualResponse();
        return buildResponse();
    }

    public synchronized void setGeometryShadow(GeometryShadowSnapshot snapshot) {
        geometryShadow = snapshot;
    }

    public synchronized void setSequenceDecoderShadow(com.handwash.recognition.ShadowSequenceSnapshot snapshot) {
        sequenceDecoderShadow = snapshot;
    }

    /** Stable low-cardinality reason for the latest rejected partial-mode observation. */
    public synchronized String getUltimoCodigoRechazoIntencion() {
        return "PROTOCOLO_OMS".equals(modoEvaluacion) ? null : intencion.ultimoCodigoRechazo();
    }

    private WashStateResponse buildResponse() {
        WashStateResponse response = new WashStateResponse();
        response.setSessionId(sessionId);
        response.setEstadoIntencion(intencion.estado());
        String motivoIntencion = intencion.motivo();
        if (pasoCandidatoPendiente != null && observacionesPasoCandidato > 0) {
            motivoIntencion = "Confirmando " + pasoCandidatoPendiente.getNombre() + " ("
                + observacionesPasoCandidato + "/" + OBSERVACIONES_PARA_CONFIRMAR_CAMBIO
                + " observaciones recientes)";
        }
        response.setMotivoIntencion(motivoIntencion);
        response.setTiempoConfirmacionIntencionMs(intencion.tiempoCandidatoMs());
        response.setUmbralConfirmacionIntencionMs(umbralConfirmacionIntencionMs);
        response.setManosVisibles(intencion.manosVisibles());
        response.setClaseCandidata(claseCandidata);
        response.setConfianzaCandidata(confianzaCandidata);
        response.setMessageType("STATE_UPDATE");
        response.setEstadoActual(estadoActual.getPasoActual() != null ?
            estadoActual.getPasoActual().name() : null);
        response.setConfianzaDeteccion(confianzaDeteccionActual);
        // A step box implies visible hands only while observations are recent.
        // After a gap or reset the UI must not keep a stale positive signal.
        response.setManoDetectada(estadoSesion == SessionStatus.EN_PROGRESO
            && (intencion.manosVisibles() >= 2
                || (ultimoPasoDetectado != null
                    && System.currentTimeMillis() - ultimaActividadTimestamp <= maxGapDeteccionMs)));
        response.setModoEvaluacion(modoEvaluacion == null ? "FRICCION_PARCIAL" : modoEvaluacion);
        response.setEstadoSesion(estadoSesion.name());
        response.setProgreso(new Progress(getPasosCompletados(),
            WashStep.getMovimientosFriccionSoportados()));
        response.setInfraccion(infraccionActual);
        response.setTiempoAcumuladoMs(getTiempoPasoActual());
        response.setTiempoTotalActivoMs(getTiempoTotalActivoMs());
        // The live websocket only needs the current infraction. The bounded
        // full history is sent once in SESSION_SUMMARY, not on every update.
        response.setIntentosReiniciados(getIntentosReiniciados());
        response.setUltimoErrorReinicio(ultimoErrorReinicio);
        response.setDuracionMinimaObjetivoMs(estrategia.getDuracionTotalMs());
        response.setSecuenciaFriccionObservadaCompleta(isSecuenciaFriccionObservadaCompleta());
        response.setEvaluacionFriccion(balanced == null ? null : balanced.snapshot());
        response.setGeometryShadow(geometryShadow);
        response.setDecodificadorSombra(sequenceDecoderShadow);
        return response;
    }

    private long getTiempoPasoActual() {
        if (balanced != null) return balanced.currentMs();
        return getTiempoPaso(estadoActual);
    }

    private long getTiempoPaso(WashStepState estado) {
        if (estado instanceof CompletedState) {
            return sumarTiemposAcumulados();
        }
        WashStep paso = estado.getPasoActual();
        // El tiempo del State es de reloj y puede incluir pausas sin manos.
        // Solo los intervalos consecutivos acreditados cuentan para la regla.
        return paso == null ? 0L : tiempoPorPasoMs.getOrDefault(paso, 0L);
    }

    public String getSessionId() { return sessionId; }
    public ProtocolType getProtocolo() { return protocolo; }
    public synchronized WashStepState getEstadoActual() { return estadoActual; }
    public synchronized SessionStatus getEstadoSesion() {
        return "PROTOCOLO_OMS".equals(modoEvaluacion) ? sesionOms.getStatus() : estadoSesion;
    }
    public long getInicioTimestamp() { return inicioTimestamp; }
    public synchronized int getPasosCompletados() {
        if (balanced != null) return balanced.validated();
        return "PROTOCOLO_OMS".equals(modoEvaluacion) ? sesionOms.getStepsCompleted() : pasosCompletados;
    }
    public synchronized boolean isSecuenciaFriccionObservadaCompleta() {
        if (balanced != null) return balanced.snapshot().secuenciaValidada();
        return !"PROTOCOLO_OMS".equals(modoEvaluacion) && secuenciaFriccionObservadaCompleta;
    }
    public synchronized Infraction getInfraccionActual() {
        return "PROTOCOLO_OMS".equals(modoEvaluacion) ? sesionOms.getCurrentInfraction() : infraccionActual;
    }
    public synchronized void setInfraccionActual(Infraction infraccion) {
        if ("PROTOCOLO_OMS".equals(modoEvaluacion)) {
            if (infraccion != null) sesionOms.registrarError(infraccion.getTipo(), infraccion.getDetalle(), infraccion.getPaso());
            return;
        }
        this.infraccionActual = infraccion;
        if (infraccion != null) {
            agregarAlHistorial(infraccion);
        }
    }
    public synchronized List<Infraction> getHistorialInfracciones() {
        return "PROTOCOLO_OMS".equals(modoEvaluacion) ? sesionOms.getInfractions() : List.copyOf(historialInfracciones);
    }
    public synchronized List<Infraction> getInfraccionesIntentoActual() {
        return "PROTOCOLO_OMS".equals(modoEvaluacion)
            ? sesionOms.getCurrentAttemptInfractions() : List.copyOf(infraccionesIntentoActual);
    }
    public synchronized List<WashAttemptSummary> getIntentosAnteriores() {
        return "PROTOCOLO_OMS".equals(modoEvaluacion) ? sesionOms.getAttempts() : List.copyOf(intentosAnteriores);
    }
    public synchronized int getNumeroIntentoActual() {
        if (balanced != null) return balanced.number();
        return "PROTOCOLO_OMS".equals(modoEvaluacion) ? sesionOms.getCurrentAttempt() : numeroIntentoActual;
    }
    public synchronized int getIntentosReiniciados() {
        if (balanced != null) return balanced.number() - 1;
        return "PROTOCOLO_OMS".equals(modoEvaluacion) ? sesionOms.getRetries() : intentosReiniciados;
    }
    public synchronized long getInfraccionesOmitidas() {
        return "PROTOCOLO_OMS".equals(modoEvaluacion) ? sesionOms.getOmittedInfractions() : infraccionesOmitidas;
    }
    public synchronized Map<WashStep, Long> getTiempoPorPasoMs() {
        if (balanced != null) {
            Map<WashStep, Long> times = new EnumMap<>(WashStep.class);
            balanced.snapshot().pasos().forEach(step -> times.put(WashStep.valueOf(step.paso()), step.tiempoAcreditadoMs()));
            return Collections.unmodifiableMap(times);
        }
        return Collections.unmodifiableMap(new EnumMap<>(tiempoPorPasoMs));
    }

    public synchronized WashStep getPasoPendienteValidacion() { return pasoPendienteValidacion; }
    public synchronized long getTiempoPendienteValidacionMs() { return tiempoPendienteValidacionMs; }
    public synchronized long getTiempoTotalActivoMs() {
        if (balanced != null) return balanced.totalMs();
        return "PROTOCOLO_OMS".equals(modoEvaluacion)
            ? sesionOms.getTotalActiveMs()
            : sumarTiemposAcumulados();
    }

    private long sumarTiemposAcumulados() {
        long total = 0L;
        for (long tiempoPasoMs : tiempoPorPasoMs.values()) total += tiempoPasoMs;
        return total;
    }
    public synchronized void limpiarPasoPendienteValidacion() {
        this.pasoPendienteValidacion = null;
        this.tiempoPendienteValidacionMs = 0;
    }

    public synchronized void expirar() {
        expirar(MotivoExpiracion.IDLE_TIMEOUT);
    }

    public synchronized MotivoExpiracion getMotivoExpiracion() { return motivoExpiracion; }

    public synchronized void expirar(MotivoExpiracion motivo) {
        if (motivo == null) throw new IllegalArgumentException("El motivo de expiración es obligatorio");
        if (getEstadoSesion() == SessionStatus.COMPLETADA || getEstadoSesion() == SessionStatus.EXPIRADA) return;
        motivoExpiracion = motivo;
        if (balanced != null) {
            balanced.interrupt(motivo.name());
            estadoSesion = SessionStatus.EXPIRADA;
            finTimestamp = System.currentTimeMillis();
            finMonotonicNanos = monotonicNanos.getAsLong();
            return;
        }
        if ("PROTOCOLO_OMS".equals(modoEvaluacion)) {
            sesionOms.expire();
            return;
        }
        if (estadoSesion != SessionStatus.COMPLETADA && estadoSesion != SessionStatus.EXPIRADA) {
            if (estadoSesion == SessionStatus.EN_PROGRESO && numeroIntentoActual > 0) {
                registrarInfraccion(InfractionType.EVIDENCIA_VISUAL_INTERRUPTA,
                    motivo == MotivoExpiracion.IDLE_TIMEOUT
                        ? "La sesión expiró por inactividad antes de completar el lavado; repita desde PASO_1_PALMAS"
                        : "La sesión se interrumpió por un error de procesamiento; reinicie la sesión",
                    estadoActual.getPasoActual());
                reiniciarIntento(motivo == MotivoExpiracion.IDLE_TIMEOUT
                    ? "SESION_EXPIRADA_POR_INACTIVIDAD" : "SESION_INTERRUMPIDA_POR_ERROR_PROCESAMIENTO");
            }
            estadoSesion = SessionStatus.EXPIRADA;
            infraccionActual = null;
            intencion.reiniciar();
            limpiarCandidatoSecuencia();
            claseCandidata = null;
            confianzaCandidata = 0.0f;
            confianzaDeteccionActual = 0.0f;
            tiempoIntencionConfirmadaMs = 0L;
            ultimoPasoDetectado = null;
            ultimoTimestampDeteccion = 0L;
            finTimestamp = System.currentTimeMillis();
            finMonotonicNanos = monotonicNanos.getAsLong();
            ultimaActividadTimestamp = finTimestamp;
            ultimaActividadMonotonicNanos = finMonotonicNanos;
        }
    }

    /**
     * Inactivity uses both wall and monotonic elapsed time: wall time includes
     * machine sleep, while monotonic time prevents a backwards clock correction
     * from extending an idle session.
     */
    public synchronized boolean estaInactivaDesde(long ahoraEpochMs, long ahoraNanos, long timeoutMs) {
        if (sesionDeTurno) return false;
        if ("PROTOCOLO_OMS".equals(modoEvaluacion)) {
            return sesionOms.isInactiveSince(ahoraEpochMs, ahoraNanos, timeoutMs);
        }
        return estadoSesion != SessionStatus.COMPLETADA && estadoSesion != SessionStatus.EXPIRADA
            && getTiempoInactivoMsInterno(ahoraEpochMs, ahoraNanos) >= timeoutMs;
    }

    public synchronized long getTiempoInactivoMs(long ahoraEpochMs, long ahoraNanos) {
        if ("PROTOCOLO_OMS".equals(modoEvaluacion)) {
            return sesionOms.getTiempoInactivoMs(ahoraEpochMs, ahoraNanos);
        }
        return getTiempoInactivoMsInterno(ahoraEpochMs, ahoraNanos);
    }

    private long getTiempoInactivoMsInterno(long ahoraEpochMs, long ahoraNanos) {
        long wallElapsedMs = Math.max(0L, ahoraEpochMs - ultimaActividadTimestamp);
        long monotonicElapsedMs = Math.max(0L,
            TimeUnit.NANOSECONDS.toMillis(ahoraNanos - ultimaActividadMonotonicNanos));
        return Math.max(wallElapsedMs, monotonicElapsedMs);
    }

    private void marcarActividad() {
        ultimaActividadTimestamp = System.currentTimeMillis();
        ultimaActividadMonotonicNanos = monotonicNanos.getAsLong();
    }

    public synchronized void setSesionDeTurno(boolean sesionDeTurno) { this.sesionDeTurno = sesionDeTurno; }
    public synchronized boolean isSesionDeTurno() { return sesionDeTurno; }

    public boolean isBalancedFriction() { return balanced != null; }

    public synchronized FrictionAttemptRecord pendingFrictionRecord() {
        return balanced == null ? null : balanced.pendingRecord();
    }

    public synchronized void frictionPersisted(FrictionAttemptRecord record) {
        if (balanced != null) balanced.persisted(record);
    }

    public synchronized void frictionPersistenceFailed() {
        if (balanced != null) balanced.persistenceFailed();
    }

    public synchronized void frictionProducerReplaced() {
        if (balanced != null) {
            balanced.producerReplaced();
            intencion.cambiarProductor();
        }
    }

    public synchronized void checkFrictionFreshness(long nowMs, long presenceGapMs) {
        if (balanced != null) balanced.checkFreshness(nowMs, presenceGapMs);
    }

    public synchronized void closeFrictionAttempt() {
        if (balanced == null || estadoSesion == SessionStatus.EXPIRADA) return;
        balanced.interrupt("SESSION_CLOSED");
        estadoSesion = SessionStatus.EXPIRADA;
        // Freeze the clock: getDuracionMs() stops at the close instead of growing forever.
        finTimestamp = System.currentTimeMillis();
        finMonotonicNanos = monotonicNanos.getAsLong();
    }

    /** Wall-clock time of the last presence pulse from the producer, whatever the hand count; creation time before any. */
    public long lastProducerPulseEpochMs() { return lastProducerPulseEpochMs; }

    public synchronized boolean observeFrictionPresence(int hands, long nowMs, long maxGapMs) {
        lastProducerPulseEpochMs = System.currentTimeMillis();
        if (balanced == null || estadoSesion == SessionStatus.EXPIRADA) return false;
        boolean changedAttempt = balanced.presence(hands, nowMs, maxGapMs);
        if (changedAttempt) {
            intencion.reiniciar();
            claseCandidata = null;
            confianzaCandidata = 0;
            limpiarCandidatoSecuencia();
        }
        syncBalancedState();
        return changedAttempt;
    }

    private void syncBalancedState() {
        estadoSesion = balanced.started() ? SessionStatus.EN_PROGRESO : SessionStatus.ESPERANDO_INICIO;
        estadoActual = !balanced.started() ? new WaitingToStartState() : switch (balanced.expected()) {
            case PASO_1_PALMAS -> new PalmsState();
            case PASO_2_DORSOS -> new BackOfHandsState();
            case PASO_3_INTERDIGITALES -> new InterlacedFingersState();
            case PASO_4_NUDILLOS -> new KnucklesState();
            case PASO_5_PULGAR -> new ThumbState();
            case PASO_6_PUNTA_DE_DEDOS -> new FingertipsState();
            default -> throw new IllegalStateException("Unsupported friction movement");
        };
    }

    /** Keeps a live, authenticated bilateral-presence session from expiring while the user frames their hands. */
    public synchronized void registrarPresenciaBilateral(long receivedAtEpochMs, long receivedAtMonotonicNanos) {
        if (estadoSesion == SessionStatus.COMPLETADA || estadoSesion == SessionStatus.EXPIRADA) return;
        if ("PROTOCOLO_OMS".equals(modoEvaluacion)) {
            sesionOms.registrarPresenciaBilateral(receivedAtEpochMs, receivedAtMonotonicNanos);
            return;
        }
        if (receivedAtEpochMs > 0L && receivedAtMonotonicNanos > ultimaActividadMonotonicNanos) {
            ultimaActividadTimestamp = receivedAtEpochMs;
            ultimaActividadMonotonicNanos = receivedAtMonotonicNanos;
        }
    }

    public synchronized long getDuracionMs() {
        if ("PROTOCOLO_OMS".equals(modoEvaluacion)) return sesionOms.getDurationMs();
        long finishedAt = finTimestamp > 0L ? finMonotonicNanos : monotonicNanos.getAsLong();
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(finishedAt - inicioMonotonicNanos));
    }

    public synchronized long getUltimaActividadTimestamp() {
        return "PROTOCOLO_OMS".equals(modoEvaluacion) ? sesionOms.getLastActivityMs() : ultimaActividadTimestamp;
    }
    public ValidationRuleStrategy getEstrategia() { return estrategia; }

    public synchronized double getConfianzaPromedio() {
        if ("PROTOCOLO_OMS".equals(modoEvaluacion)) return sesionOms.getAverageConfidence();
        return cantidadDetecciones == 0 ? 0.0 : sumaConfianzas / cantidadDetecciones;
    }

    public synchronized String getModoEvaluacion() {
        return modoEvaluacion == null ? "FRICCION_PARCIAL" : modoEvaluacion;
    }
    public synchronized String getModoEvaluacionSeleccionado() { return modoEvaluacion; }
    public synchronized boolean admiteModo(String modo) {
        return modoEvaluacion == null || modoEvaluacion.equals(modo);
    }
    public synchronized boolean isProcedimientoCompletoValidado() {
        return "PROTOCOLO_OMS".equals(modoEvaluacion)
            ? sesionOms.isProcedureValidated() : estrategia.procedimientoCompletoValidado();
    }
    public synchronized List<String> getAccionesNoDetectadas() {
        return "PROTOCOLO_OMS".equals(modoEvaluacion)
            ? sesionOms.getMissingActions() : estrategia.accionesNoDetectadas();
    }
    public synchronized Map<String, Long> getTiempoPorAccionOmsMs() {
        return sesionOms.getActiveMsByAction();
    }
    public synchronized Map<String, String> getCoberturaJabon() {
        return sesionOms.getCoverageStates();
    }
    public synchronized boolean isCoberturaJabonCompleta() {
        return sesionOms.isSoapCoverageComplete();
    }
    public synchronized long getTiempoTotalActivoOmsMs() { return sesionOms.getTotalActiveMs(); }
    public synchronized long getOmsMinimumTotalMs() { return sesionOms.getMinimumTotalMs(); }
}
