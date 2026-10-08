package com.handwash.model;

import com.handwash.state.*;
import com.handwash.strategy.ReglaValidacionStrategy;
import com.handwash.strategy.ReglaValidacionStrategyFactory;
import com.handwash.intention.CadenaIntencionLavado;
import com.handwash.intention.SeguimientoIntencion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;

public class SesionLavado {
    private final String sessionId;
    private final TipoProtocolo protocolo;
    private final ReglaValidacionStrategy estrategia;
    private final long maxGapDeteccionMs;
    private final long maxGapTransicionMs;
    private final SesionOms sesionOms;
    private final SeguimientoIntencion intencion = new SeguimientoIntencion();
    private String modoEvaluacion;
    private PasoLavadoState estadoActual;
    private EstadoSesion estadoSesion;
    private final long inicioTimestamp;
    private final long inicioMonotonicNanos;
    private int pasosCompletados;
    /** Último error del intento; se conserva para que el WebSocket coalescido no lo pierda. */
    private Infraccion infraccionActual;
    private Infraccion ultimoErrorReinicio;
    private final List<Infraccion> historialInfracciones = new ArrayList<>();
    private final List<Infraccion> infraccionesIntentoActual = new ArrayList<>();
    private final List<IntentoLavadoResumen> intentosAnteriores = new ArrayList<>();
    private static final int MAX_HISTORIAL_INFRACCIONES = 500;
    private static final int MAX_INFRACCIONES_POR_INTENTO = 50;
    private static final int MAX_INTENTOS_GUARDADOS = 100;
    private long ultimaInfraccionTimestamp;
    private int intentoUltimaInfraccion = -1;
    private long infraccionesOmitidas;
    private final Map<PasoLavado, Long> tiempoPorPasoMs = new EnumMap<>(PasoLavado.class);

    private PasoLavado pasoPendienteValidacion;
    private long tiempoPendienteValidacionMs;
    private long ultimaActividadTimestamp;
    private long ultimaActividadMonotonicNanos;
    private long finTimestamp;
    private long finMonotonicNanos;
    private long ultimoTimestampDeteccion;
    private PasoLavado ultimoPasoDetectado;
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
    private PasoLavado pasoCandidatoPendiente;
    private int observacionesPasoCandidato;
    private long ultimaObservacionPasoCandidato;
    private long inicioPasoCandidatoTimestamp;
    private long tiempoPasoCandidatoConfirmadoMs;
    private static final int OBSERVACIONES_PARA_CONFIRMAR_CAMBIO = 2;
    private static final long DEFAULT_MAX_GAP_DETECCION_MS = 1500L;
    private static final long DEFAULT_MAX_GAP_TRANSICION_MS = 650L;

    public SesionLavado(String sessionId, TipoProtocolo protocolo) {
        this(sessionId, protocolo, new ReglaValidacionStrategyFactory().crear(protocolo));
    }

    public SesionLavado(String sessionId, TipoProtocolo protocolo, ReglaValidacionStrategy estrategia) {
        this(sessionId, protocolo, estrategia, DEFAULT_MAX_GAP_DETECCION_MS);
    }

    public SesionLavado(String sessionId, TipoProtocolo protocolo,
                        ReglaValidacionStrategy estrategia, long maxGapDeteccionMs) {
        this(sessionId, protocolo, estrategia, maxGapDeteccionMs,
            DEFAULT_MAX_GAP_TRANSICION_MS, false);
    }

    public SesionLavado(String sessionId, TipoProtocolo protocolo,
                        ReglaValidacionStrategy estrategia, long maxGapDeteccionMs,
                        boolean omsModelReady) {
        this(sessionId, protocolo, estrategia, maxGapDeteccionMs,
            DEFAULT_MAX_GAP_TRANSICION_MS, omsModelReady);
    }

    public SesionLavado(String sessionId, TipoProtocolo protocolo,
                        ReglaValidacionStrategy estrategia, long maxGapDeteccionMs,
                        long maxGapTransicionMs, boolean omsModelReady) {
        this.sessionId = sessionId;
        this.protocolo = protocolo;
        this.estrategia = estrategia;
        if (maxGapDeteccionMs < 100L || maxGapDeteccionMs > 5_000L) {
            throw new IllegalArgumentException("maxGapDeteccionMs debe estar entre 100 y 5000");
        }
        if (maxGapTransicionMs < 100L || maxGapTransicionMs > 1_500L) {
            throw new IllegalArgumentException("maxGapTransicionMs debe estar entre 100 y 1500");
        }
        this.maxGapDeteccionMs = maxGapDeteccionMs;
        this.maxGapTransicionMs = maxGapTransicionMs;
        this.sesionOms = new SesionOms(
            sessionId, omsModelReady, maxGapDeteccionMs, estrategia.getDuracionTotalMs(),
            estrategia.getDuracionMinimaFaseOmsMs());
        long startedAtEpochMs = System.currentTimeMillis();
        long startedAtMonotonicNanos = System.nanoTime();
        this.inicioTimestamp = startedAtEpochMs;
        this.inicioMonotonicNanos = startedAtMonotonicNanos;
        this.ultimaActividadTimestamp = this.inicioTimestamp;
        this.ultimaActividadMonotonicNanos = startedAtMonotonicNanos;
        this.estadoActual = new EsperandoInicioState();
        this.estadoSesion = EstadoSesion.ESPERANDO_INICIO;
        this.pasosCompletados = 0;
    }

    /** Se ejecuta dentro del lock de sesión antes de State; rechazar nunca suma tiempo. */
    public synchronized boolean evaluarIntencion(DeteccionEvento evento, CadenaIntencionLavado cadena) {
        tiempoIntencionConfirmadaMs = 0L;
        intencion.limpiarUltimoCodigoRechazo();
        // Una observación vieja no puede pausar, reiniciar ni acreditar un
        // intento más nuevo, incluso cuando su etiqueta sea Fondo.
        if (intencion.esRepetida(evento)) return false;
        PasoLavado pasoCandidato = evento.getPasoLavadoResuelto();
        claseCandidata = pasoCandidato == null
            ? evento.getClaseDetectada() : pasoCandidato.name();
        confianzaCandidata = evento.getConfianza();
        umbralConfirmacionIntencionMs = cadena.confirmacionMs();
        boolean activo = estadoSesion == EstadoSesion.EN_PROGRESO;
        if (activo && intencion.debeReiniciar(evento.getServerReceivedAtMonotonicMs(), cadena)) {
            procesarDeteccionSinRespuesta(PasoLavado.FONDO,
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
            limpiarCandidatoSecuencia();
            ultimoTimestampDeteccion = 0;
            ultimoPasoDetectado = null;
            boolean sinEvidencia = evento.getPasoLavadoResuelto() == PasoLavado.FONDO;
            if (activo && (sinEvidencia || intencion.debeReiniciar(evento.getServerReceivedAtMonotonicMs(), cadena))) {
                procesarDeteccionSinRespuesta(PasoLavado.FONDO,
                    evento.getServerReceivedAtMonotonicMs(), 1.0f);
            }
        }
        return permitido;
    }

    /** Rejected fresh observations break transition votes and active dwell timing. */
    public synchronized void descartarDeteccion(DeteccionEvento evento) {
        descartarDeteccion(evento, null);
    }

    /** Rejected input can provide a precise dashboard reason without changing session rules. */
    public synchronized void descartarDeteccion(DeteccionEvento evento, String motivo) {
        tiempoIntencionConfirmadaMs = 0L;
        if ("PROTOCOLO_OMS".equals(modoEvaluacion)) {
            sesionOms.descartarObservacion(motivo);
            return;
        }
        if (intencion.esRepetida(evento)) return;
        boolean activo = estadoSesion == EstadoSesion.EN_PROGRESO;
        if (motivo == null) intencion.descartar(evento, activo);
        else intencion.descartar(evento, activo, motivo);
        limpiarCandidatoSecuencia();
        // Do not credit the gap spanning a rejected frame to the previous phase.
        ultimoTimestampDeteccion = 0L;
        ultimoPasoDetectado = null;
    }

    public synchronized EstadoLavadoResponse procesarDeteccion(PasoLavado claseDetectada, long timestampMs) {
        return procesarDeteccion(claseDetectada, timestampMs, 1.0f);
    }

    public synchronized EstadoLavadoResponse procesarDeteccion(
        PasoLavado claseDetectada, long timestampMs, float confianza
    ) {
        return procesarDeteccionInterno(claseDetectada, timestampMs, confianza, true);
    }

    /** Processes a live frame without allocating a response DTO that the camera pipeline discards. */
    public synchronized void procesarDeteccionSinRespuesta(
        PasoLavado claseDetectada, long timestampMs, float confianza
    ) {
        procesarDeteccionInterno(claseDetectada, timestampMs, confianza, false);
    }

    private EstadoLavadoResponse procesarDeteccionInterno(
        PasoLavado claseDetectada, long timestampMs, float confianza, boolean incluirRespuesta
    ) {
        if (modoEvaluacion == null) modoEvaluacion = "FRICCION_PARCIAL";
        if (!"FRICCION_PARCIAL".equals(modoEvaluacion)) {
            registrarInfraccion(TipoInfraccion.MODO_DETECCION_INCOMPATIBLE,
                "Esta sesión ya está evaluando el protocolo OMS completo", claseDetectada);
            return crearRespuestaSiSolicitada(incluirRespuesta);
        }
        if (estadoSesion == EstadoSesion.COMPLETADA || estadoSesion == EstadoSesion.EXPIRADA) {
            return crearRespuestaSiSolicitada(incluirRespuesta);
        }

        if (claseDetectada == null) {
            return crearRespuestaSiSolicitada(incluirRespuesta);
        }

        long eventoTimestamp = timestampMs > 0 ? timestampMs : System.currentTimeMillis();
        if (ultimoTimestampDeteccion > 0 && eventoTimestamp < ultimoTimestampDeteccion) {
            // Un frame retrasado no debe adelantar el reloj ni alterar la secuencia.
            return crearRespuestaSiSolicitada(incluirRespuesta);
        }
        PasoLavadoState estadoAnterior = estadoActual;
        PasoLavado pasoAnterior = estadoAnterior.getPasoActual();
        if (claseDetectada == PasoLavado.FONDO) {
            // El capturador solo envía Fondo tras una pérdida sostenida de
            // evidencia. Reinicia el intento porque no se puede verificar lo
            // ocurrido mientras las manos estuvieron fuera de cámara.
            if (pasoAnterior != null && numeroIntentoActual > 0) {
                long tiempoObservadoMs = getTiempoPaso(estadoAnterior);
                if (!estrategia.validarTiempoPaso(pasoAnterior, tiempoObservadoMs)) {
                    registrarInfraccion(TipoInfraccion.TIEMPO_INSUFICIENTE,
                        String.format("Paso %s: %.1fs observados / %.1fs requeridos antes de perder evidencia",
                            pasoAnterior.getNombre(),
                            tiempoObservadoMs / 1000.0,
                            estrategia.getTiempoRequeridoPaso(pasoAnterior) / 1000.0),
                        pasoAnterior);
                }
                registrarInfraccion(TipoInfraccion.EVIDENCIA_VISUAL_INTERRUPTA,
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

        if (pasoAnterior == null && claseDetectada != PasoLavado.PASO_1_PALMAS) {
            registrarInfraccion(TipoInfraccion.PASO_OMITIDO,
                detalleReinicioPorSecuencia(claseDetectada), claseDetectada);
            return crearRespuestaSiSolicitada(incluirRespuesta);
        }
        if (pasoAnterior != null && claseDetectada != pasoAnterior
            && claseDetectada != pasoAnterior.siguiente()) {
            if (!confirmarCandidatoPaso(claseDetectada, eventoTimestamp)) {
                return crearRespuestaSiSolicitada(incluirRespuesta);
            }
            limpiarCandidatoSecuencia();
            if (pasoAnterior == PasoLavado.PASO_7_CIRCULARES
                && !estrategia.validarTiempoPaso(pasoAnterior, getTiempoPaso(estadoAnterior))) {
                long tiempoPasoFinalMs = getTiempoPaso(estadoAnterior);
                registrarInfraccion(TipoInfraccion.TIEMPO_INSUFICIENTE,
                    String.format("Paso %s: %.1fs / %.1fs requeridos; repita desde PASO_1_PALMAS",
                        pasoAnterior.getNombre(),
                        tiempoPasoFinalMs / 1000.0,
                        estrategia.getTiempoRequeridoPaso(pasoAnterior) / 1000.0),
                    pasoAnterior);
                reiniciarIntento("TIEMPO_INSUFICIENTE_PASO_7_CIRCULARES");
                return crearRespuestaSiSolicitada(incluirRespuesta);
            }
            TipoInfraccion tipo = claseDetectada.getNumero() > pasoAnterior.getNumero()
                ? TipoInfraccion.PASO_OMITIDO : TipoInfraccion.PASO_INVALIDO;
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
        PasoLavado pasoNuevo = estadoActual.getPasoActual();
        if (pasoAnterior == null && pasoNuevo == PasoLavado.PASO_1_PALMAS) {
            iniciarIntento(eventoTimestamp);
        }
        if (pasoNuevo != null && pasoNuevo != pasoAnterior) {
            if (tiempoFaseNuevaConfirmadaMs > 0L) {
                tiempoPorPasoMs.merge(pasoNuevo, tiempoFaseNuevaConfirmadaMs, Long::sum);
            }
            ultimoPasoDetectado = pasoNuevo;
            ultimoTimestampDeteccion = eventoTimestamp;
        }

        // El último paso no depende de que el siguiente frame contenga otra
        // clase. Se cierra cuando la Strategy confirma su duración mínima.
        // Esto evita que una detección errónea posterior marque la sesión como
        // completada y permite emitir el resumen en el último frame válido.
        if (!estadoActual.isCompletado()
            && claseDetectada == PasoLavado.PASO_7_CIRCULARES
            && pasoNuevo == PasoLavado.PASO_7_CIRCULARES
            && estrategia.validarTiempoPaso(pasoNuevo, getTiempoPasoActual())) {
            tiempoPorPasoMs.put(pasoNuevo, getTiempoPasoActual());
            pasoPendienteValidacion = pasoNuevo;
            tiempoPendienteValidacionMs = getTiempoPasoActual();
            estadoActual = new CompletoState();
            pasoNuevo = null;
        }

        if (estadoSesion == EstadoSesion.ESPERANDO_INICIO && estadoActual.getPasoActual() != null) {
            estadoSesion = EstadoSesion.EN_PROGRESO;
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
            estadoSesion = EstadoSesion.COMPLETADA;
            pasosCompletados = 7;
            finTimestamp = System.currentTimeMillis();
            finMonotonicNanos = System.nanoTime();
        } else if (pasoNuevo != null) {
            pasosCompletados = Math.max(pasosCompletados, pasoNuevo.getNumero() - 1);
        }

        return crearRespuestaSiSolicitada(incluirRespuesta);
    }

    private EstadoLavadoResponse crearRespuestaSiSolicitada(boolean incluirRespuesta) {
        return incluirRespuesta ? buildResponse() : null;
    }

    public synchronized EstadoLavadoResponse procesarAccionOms(
        AccionOms accion, Map<String, EvidenciaJabon> evidencia, long timestampMs, float confianza
    ) {
        if (modoEvaluacion == null) modoEvaluacion = "PROTOCOLO_OMS";
        if (!"PROTOCOLO_OMS".equals(modoEvaluacion)) {
            sesionOms.registrarError(TipoInfraccion.MODO_DETECCION_INCOMPATIBLE,
                "Esta sesión ya está evaluando únicamente movimientos de fricción",
                accion == null ? null : accion.name());
            return buildResponse();
        }
        return sesionOms.procesar(accion, evidencia, timestampMs, confianza);
    }

    private void actualizarTiempoActivo(PasoLavado claseDetectada, long eventoTimestamp) {
        PasoLavado pasoActual = estadoActual.getPasoActual();
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
    private boolean confirmarCandidatoPaso(PasoLavado detectado, long eventoTimestamp) {
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
            PasoLavado pasoActual = estadoActual.getPasoActual();
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

    public synchronized void registrarInfraccion(TipoInfraccion tipo, String detalle, PasoLavado paso) {
        if ("PROTOCOLO_OMS".equals(modoEvaluacion)) {
            sesionOms.registrarError(tipo, detalle, paso == null ? null : paso.name());
            return;
        }
        long ahora = System.currentTimeMillis();
        Infraccion infraccion = new Infraccion(
            tipo,
            detalle,
            paso != null ? paso.name() : null,
            Instant.ofEpochMilli(ahora).toString()
        );
        Infraccion ultima = historialInfracciones.isEmpty() ? null
            : historialInfracciones.get(historialInfracciones.size() - 1);
        if (intentoUltimaInfraccion == numeroIntentoActual
            && ultima != null && ultima.getTipo() == tipo
            && java.util.Objects.equals(ultima.getPaso(), infraccion.getPaso())
            && java.util.Objects.equals(ultima.getDetalle(), detalle)
            && ahora - ultimaInfraccionTimestamp < 2_000L) {
            this.infraccionActual = ultima;
            return;
        }
        this.infraccionActual = infraccion;
        agregarAlHistorial(infraccion, ahora);
    }

    private void agregarAlHistorial(Infraccion infraccion, long ahora) {
        if (historialInfracciones.size() >= MAX_HISTORIAL_INFRACCIONES) {
            historialInfracciones.remove(0);
            infraccionesOmitidas++;
        }
        historialInfracciones.add(infraccion);
        if (infraccionesIntentoActual.size() >= MAX_INFRACCIONES_POR_INTENTO) {
            infraccionesIntentoActual.remove(0);
        }
        infraccionesIntentoActual.add(infraccion);
        ultimaInfraccionTimestamp = ahora;
        intentoUltimaInfraccion = numeroIntentoActual;
    }

    private void iniciarIntento(long eventoTimestamp) {
        numeroIntentoActual++;
        limpiarCandidatoSecuencia();
        long tiempoPalmasConfirmadasMs = tiempoIntencionConfirmadaMs;
        tiempoIntencionConfirmadaMs = 0L;
        inicioIntentoMonotonicNanos = System.nanoTime()
            - TimeUnit.MILLISECONDS.toNanos(tiempoPalmasConfirmadasMs);
        infraccionActual = null;
        infraccionesIntentoActual.clear();
        tiempoPorPasoMs.clear();
        if (tiempoPalmasConfirmadasMs > 0L) {
            tiempoPorPasoMs.put(PasoLavado.PASO_1_PALMAS, tiempoPalmasConfirmadasMs);
        }
        ultimoTimestampDeteccion = eventoTimestamp;
        ultimoPasoDetectado = PasoLavado.PASO_1_PALMAS;
        pasosCompletados = 0;
        estadoSesion = EstadoSesion.EN_PROGRESO;
    }

    private String detalleReinicioPorSecuencia(PasoLavado detectado) {
        return "Secuencia incorrecta: se detectó " + detectado.name()
            + "; vuelva a iniciar desde PASO_1_PALMAS";
    }

    /**
     * Cierra y conserva el intento fallido, y vuelve la State machine al paso inicial.
     * El historial de sesión no se borra, pero los tiempos de un intento no se
     * mezclan con los del siguiente.
     */
    public synchronized void reiniciarIntento(String motivo) {
        intencion.reiniciar();
        limpiarCandidatoSecuencia();
        if ("PROTOCOLO_OMS".equals(modoEvaluacion)) {
            sesionOms.reiniciarIntento(motivo);
            return;
        }
        if (estadoSesion == EstadoSesion.EN_PROGRESO && numeroIntentoActual > 0) {
            ultimoErrorReinicio = infraccionActual;
            Map<String, Long> times = new LinkedHashMap<>();
            tiempoPorPasoMs.forEach((paso, milliseconds) -> times.put(paso.name(), milliseconds));
            IntentoLavadoResumen resumen = new IntentoLavadoResumen(
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
        estadoActual = new EsperandoInicioState();
        estadoSesion = EstadoSesion.ESPERANDO_INICIO;
        pasosCompletados = 0;
        tiempoPorPasoMs.clear();
        pasoPendienteValidacion = null;
        tiempoPendienteValidacionMs = 0L;
        ultimoTimestampDeteccion = 0L;
        ultimoPasoDetectado = null;
        inicioIntentoMonotonicNanos = 0L;
        tiempoIntencionConfirmadaMs = 0L;
        infraccionesIntentoActual.clear();
    }

    private long duracionIntentoMonotonicaMs() {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(
            System.nanoTime() - inicioIntentoMonotonicNanos));
    }

    public synchronized EstadoLavadoResponse getEstadoActualResponse() {
        if ("PROTOCOLO_OMS".equals(modoEvaluacion)) return sesionOms.getEstadoActualResponse();
        return buildResponse();
    }

    /** Stable low-cardinality reason for the latest rejected partial-mode observation. */
    public synchronized String getUltimoCodigoRechazoIntencion() {
        return "PROTOCOLO_OMS".equals(modoEvaluacion) ? null : intencion.ultimoCodigoRechazo();
    }

    private EstadoLavadoResponse buildResponse() {
        EstadoLavadoResponse response = new EstadoLavadoResponse();
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
        response.setManoDetectada(estadoSesion == EstadoSesion.EN_PROGRESO
            && (intencion.manosVisibles() >= 2
                || (ultimoPasoDetectado != null
                    && System.currentTimeMillis() - ultimaActividadTimestamp <= maxGapDeteccionMs)));
        response.setModoEvaluacion(modoEvaluacion == null ? "FRICCION_PARCIAL" : modoEvaluacion);
        response.setEstadoSesion(estadoSesion.name());
        response.setProgreso(new Progreso(pasosCompletados, 7));
        response.setInfraccion(infraccionActual);
        response.setTiempoAcumuladoMs(getTiempoPasoActual());
        response.setTiempoTotalActivoMs(getTiempoTotalActivoMs());
        // The live websocket only needs the current infraction. The bounded
        // full history is sent once in SESSION_SUMMARY, not on every update.
        response.setIntentosReiniciados(intentosReiniciados);
        response.setUltimoErrorReinicio(ultimoErrorReinicio);
        response.setDuracionMinimaObjetivoMs(estrategia.getDuracionTotalMs());
        return response;
    }

    private long getTiempoPasoActual() {
        return getTiempoPaso(estadoActual);
    }

    private long getTiempoPaso(PasoLavadoState estado) {
        if (estado instanceof CompletoState) {
            return sumarTiemposAcumulados();
        }
        PasoLavado paso = estado.getPasoActual();
        // El tiempo del State es de reloj y puede incluir pausas sin manos.
        // Solo los intervalos consecutivos acreditados cuentan para la regla.
        return paso == null ? 0L : tiempoPorPasoMs.getOrDefault(paso, 0L);
    }

    public String getSessionId() { return sessionId; }
    public TipoProtocolo getProtocolo() { return protocolo; }
    public synchronized PasoLavadoState getEstadoActual() { return estadoActual; }
    public synchronized EstadoSesion getEstadoSesion() {
        return "PROTOCOLO_OMS".equals(modoEvaluacion) ? sesionOms.getStatus() : estadoSesion;
    }
    public long getInicioTimestamp() { return inicioTimestamp; }
    public synchronized int getPasosCompletados() {
        return "PROTOCOLO_OMS".equals(modoEvaluacion) ? sesionOms.getStepsCompleted() : pasosCompletados;
    }
    public synchronized Infraccion getInfraccionActual() {
        return "PROTOCOLO_OMS".equals(modoEvaluacion) ? sesionOms.getCurrentInfraction() : infraccionActual;
    }
    public synchronized void setInfraccionActual(Infraccion infraccion) {
        if ("PROTOCOLO_OMS".equals(modoEvaluacion)) {
            if (infraccion != null) sesionOms.registrarError(infraccion.getTipo(), infraccion.getDetalle(), infraccion.getPaso());
            return;
        }
        this.infraccionActual = infraccion;
        if (infraccion != null) {
            agregarAlHistorial(infraccion, System.currentTimeMillis());
        }
    }
    public synchronized List<Infraccion> getHistorialInfracciones() {
        return "PROTOCOLO_OMS".equals(modoEvaluacion) ? sesionOms.getInfractions() : List.copyOf(historialInfracciones);
    }
    public synchronized List<Infraccion> getInfraccionesIntentoActual() {
        return "PROTOCOLO_OMS".equals(modoEvaluacion)
            ? sesionOms.getCurrentAttemptInfractions() : List.copyOf(infraccionesIntentoActual);
    }
    public synchronized List<IntentoLavadoResumen> getIntentosAnteriores() {
        return "PROTOCOLO_OMS".equals(modoEvaluacion) ? sesionOms.getAttempts() : List.copyOf(intentosAnteriores);
    }
    public synchronized int getNumeroIntentoActual() {
        return "PROTOCOLO_OMS".equals(modoEvaluacion) ? sesionOms.getCurrentAttempt() : numeroIntentoActual;
    }
    public synchronized int getIntentosReiniciados() {
        return "PROTOCOLO_OMS".equals(modoEvaluacion) ? sesionOms.getRetries() : intentosReiniciados;
    }
    public synchronized long getInfraccionesOmitidas() {
        return "PROTOCOLO_OMS".equals(modoEvaluacion) ? sesionOms.getOmittedInfractions() : infraccionesOmitidas;
    }
    public synchronized Map<PasoLavado, Long> getTiempoPorPasoMs() { return Collections.unmodifiableMap(new EnumMap<>(tiempoPorPasoMs)); }

    public synchronized PasoLavado getPasoPendienteValidacion() { return pasoPendienteValidacion; }
    public synchronized long getTiempoPendienteValidacionMs() { return tiempoPendienteValidacionMs; }
    public synchronized long getTiempoTotalActivoMs() {
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
        if ("PROTOCOLO_OMS".equals(modoEvaluacion)) {
            sesionOms.expire();
            return;
        }
        if (estadoSesion != EstadoSesion.COMPLETADA && estadoSesion != EstadoSesion.EXPIRADA) {
            if (estadoSesion == EstadoSesion.EN_PROGRESO && numeroIntentoActual > 0) {
                registrarInfraccion(TipoInfraccion.EVIDENCIA_VISUAL_INTERRUPTA,
                    "La sesión expiró por inactividad antes de completar el lavado; repita desde PASO_1_PALMAS",
                    estadoActual.getPasoActual());
                reiniciarIntento("SESION_EXPIRADA_POR_INACTIVIDAD");
            }
            estadoSesion = EstadoSesion.EXPIRADA;
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
            finMonotonicNanos = System.nanoTime();
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
        if ("PROTOCOLO_OMS".equals(modoEvaluacion)) {
            return sesionOms.isInactiveSince(ahoraEpochMs, ahoraNanos, timeoutMs);
        }
        return estadoSesion != EstadoSesion.COMPLETADA && estadoSesion != EstadoSesion.EXPIRADA
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
        ultimaActividadMonotonicNanos = System.nanoTime();
    }

    public synchronized long getDuracionMs() {
        if ("PROTOCOLO_OMS".equals(modoEvaluacion)) return sesionOms.getDurationMs();
        long finishedAt = finTimestamp > 0L ? finMonotonicNanos : System.nanoTime();
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(finishedAt - inicioMonotonicNanos));
    }

    public synchronized long getUltimaActividadTimestamp() {
        return "PROTOCOLO_OMS".equals(modoEvaluacion) ? sesionOms.getLastActivityMs() : ultimaActividadTimestamp;
    }
    public ReglaValidacionStrategy getEstrategia() { return estrategia; }

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
    public synchronized SesionOms getSesionOms() { return sesionOms; }
}
