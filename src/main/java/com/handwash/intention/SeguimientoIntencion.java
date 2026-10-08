package com.handwash.intention;

import com.handwash.model.DeteccionEvento;

/** Memoria O(1) por sesión; no almacena frames ni un historial de detecciones. */
public final class SeguimientoIntencion {
    private long ultimaSecuencia = -1;
    private long ultimaMarcaServidorMs;
    private long ultimaObservacion;
    private long tiempoCandidato;
    private int observaciones;
    private long ultimaFriccion;
    private long ultimaActualizacionNanos;
    private boolean tieneActualizacionNanos;
    private long evidenciaManosCaducaEnNanos;
    private boolean tieneEvidenciaManosNanos;
    private int manosVisibles;
    private String estado = "SIN_EVIDENCIA";
    private String motivo = "Esperando evidencia de movimiento";
    private String ultimoCodigoRechazo;

    public boolean evaluar(DeteccionEvento evento, boolean activo, CadenaIntencionLavado cadena) {
        ultimoCodigoRechazo = null;
        long ahora = evento.getServerReceivedAtMonotonicMs();
        var evidencia = evento.getEvidenciaMovimiento();
        if (ahora <= 0) return rechazar("SIN_RELOJ_SERVIDOR", activo);
        if (evidencia != null && evento.getErrorEvidenciaMovimiento() != null)
            return rechazar("EVIDENCIA_INVALIDA", activo);
        // Un heartbeat duplicado no agrega tiempo, no refresca la observación
        // y tampoco borra votos válidos de otras observaciones.
        if (evidencia != null && evidencia.secuencia() <= ultimaSecuencia) return false;
        if (evidencia != null) ultimaSecuencia = evidencia.secuencia();
        if (ahora < ultimaMarcaServidorMs)
            return rechazar("OBSERVACION_FUERA_DE_ORDEN", activo);
        ultimaMarcaServidorMs = ahora;
        ultimaActualizacionNanos = System.nanoTime();
        tieneActualizacionNanos = true;
        actualizarVisibilidadManos(evento);
        String rechazo = cadena.evaluar(evento, activo);
        if (rechazo != null) return rechazar(rechazo, activo);
        if (activo) {
            ultimaFriccion = ahora;
            estado = "LAVADO_PROBABLE";
            motivo = "Paso YOLO confiable con ambas manos visibles";
            return true;
        }
        long intervalo = ahora - ultimaObservacion;
        if (ultimaObservacion == 0 || intervalo <= 0 || intervalo > cadena.maxIntervaloMs()) {
            tiempoCandidato = 0;
            observaciones = 1;
        } else {
            tiempoCandidato += intervalo;
            observaciones++;
        }
        ultimaObservacion = ahora;
        boolean confirmado = tiempoCandidato >= cadena.confirmacionMs()
            && observaciones >= cadena.observacionesMinimas();
        estado = confirmado ? "LAVADO_PROBABLE" : "INTENCION_CANDIDATA";
        motivo = confirmado ? "Palmas reconocidas de forma sostenida; inicia la evaluación"
            : "Confirmando reconocimiento sostenido de palmas";
        if (confirmado) ultimaFriccion = ahora;
        return confirmado;
    }

    /** A rejected fresh observation adds no vote and breaks start confirmation. */
    public void descartar(DeteccionEvento evento, boolean activo) {
        String motivoRechazo = evento != null && evento.getErrorEvidenciaMovimiento() != null
            ? "Evidencia espacial inválida; se interrumpen votos y tiempo acreditable"
            : "Confianza bajo el umbral; se interrumpen votos y tiempo acreditable";
        descartar(evento, activo, motivoRechazo);
    }

    /** Allows the application boundary to publish the actual reason for a rejection. */
    public void descartar(DeteccionEvento evento, boolean activo, String motivoRechazo) {
        if (evento != null && evento.getServerReceivedAtMonotonicMs() > ultimaMarcaServidorMs) {
            ultimaMarcaServidorMs = evento.getServerReceivedAtMonotonicMs();
        }
        var evidencia = evento == null ? null : evento.getEvidenciaMovimiento();
        if (evidencia != null) {
            Long secuencia = evidencia.secuencia();
            if (secuencia != null && secuencia >= 0 && secuencia > ultimaSecuencia) {
                ultimaSecuencia = secuencia;
            }
            actualizarVisibilidadManos(evento);
        } else {
            manosVisibles = 0;
            tieneEvidenciaManosNanos = false;
        }
        // A rejected frame cannot bridge time or votes across an uncertain gap.
        limpiarCandidato();
        ultimaActualizacionNanos = System.nanoTime();
        tieneActualizacionNanos = true;
        estado = activo ? "PAUSA"
            : manosVisibles > 0 ? "MANOS_PRESENTES" : "SIN_EVIDENCIA";
        motivo = motivoRechazo == null || motivoRechazo.isBlank()
            ? "La observación se rechazó; se interrumpen votos y tiempo acreditable"
            : motivoRechazo;
    }

    private boolean rechazar(String razon, boolean activo) {
        ultimoCodigoRechazo = razon;
        if ("GESTO_INCIERTO".equals(razon)) return rechazarPorConfianzaBaja(activo);
        limpiarCandidato();
        estado = activo ? "PAUSA" : manosVisibles > 0 ? "MANOS_PRESENTES" : "SIN_EVIDENCIA";
        motivo = razon;
        return false;
    }

    private boolean rechazarPorConfianzaBaja(boolean activo) {
        ultimoCodigoRechazo = "GESTO_INCIERTO";
        limpiarCandidato();
        estado = activo ? "PAUSA"
            : manosVisibles > 0 ? "MANOS_PRESENTES" : "SIN_EVIDENCIA";
        motivo = "La confianza no alcanzó el umbral; se reinician los votos de inicio";
        return false;
    }

    private void limpiarCandidato() {
        tiempoCandidato = 0;
        observaciones = 0;
        ultimaObservacion = 0;
    }

    public boolean debeReiniciar(long ahora, CadenaIntencionLavado cadena) {
        return ultimaFriccion > 0 && ahora - ultimaFriccion >= cadena.pausaMaximaMs();
    }

    public boolean esRepetida(DeteccionEvento evento) {
        if (evento == null) return false;
        var evidencia = evento.getEvidenciaMovimiento();
        Long secuencia = evidencia == null ? null : evidencia.secuencia();
        return secuencia != null && secuencia >= 0 && secuencia <= ultimaSecuencia;
    }

    /** Active-phase continuity follows the session sampling window, not the stricter start-vote window. */
    public boolean tieneHueco(long ahora, long maxGapDeteccionMs) {
        return ultimaFriccion > 0 && ahora - ultimaFriccion > maxGapDeteccionMs;
    }

    public void reiniciar() {
        // Secuencia y marca temporal sobreviven al reinicio; un intento nuevo
        // no puede reutilizar frames ni retroceder al tiempo de un frame viejo.
        tiempoCandidato = 0;
        observaciones = 0;
        ultimaObservacion = 0;
        ultimaFriccion = 0;
        ultimaActualizacionNanos = 0;
        tieneActualizacionNanos = false;
        manosVisibles = 0;
        tieneEvidenciaManosNanos = false;
        estado = "SIN_EVIDENCIA";
        motivo = "Debe volver a confirmar fricción de palmas";
        ultimoCodigoRechazo = null;
    }
    public void limpiarUltimoCodigoRechazo() { ultimoCodigoRechazo = null; }
    public String ultimoCodigoRechazo() { return ultimoCodigoRechazo; }
    private boolean reciente() {
        return tieneActualizacionNanos
            && actualizacionReciente(ultimaActualizacionNanos, System.nanoTime());
    }

    static boolean actualizacionReciente(long actualizadaEnNanos, long ahoraNanos) {
        long transcurrido = ahoraNanos - actualizadaEnNanos;
        return transcurrido >= 0L && transcurrido <= 1_500_000_000L;
    }
    public String estado() { return reciente() ? estado : "SIN_EVIDENCIA"; }
    public String motivo() { return reciente() ? motivo : "Esperando evidencia reciente"; }
    public long tiempoCandidatoMs() { return reciente() ? tiempoCandidato : 0; }
    public int manosVisibles() {
        if (!tieneEvidenciaManosNanos) return 0;
        return evidenciaManosCaducaEnNanos - System.nanoTime() >= 0L
            ? manosVisibles : 0;
    }

    private void actualizarVisibilidadManos(DeteccionEvento evento) {
        var evidencia = evento == null ? null : evento.getEvidenciaMovimiento();
        boolean reciente = evidencia != null
            && evento.getErrorEvidenciaMovimiento() == null
            && evento.evidenciaMovimientoReciente();
        manosVisibles = reciente ? evidencia.manosVisibles() : 0;
        tieneEvidenciaManosNanos = reciente;
        evidenciaManosCaducaEnNanos = reciente
            ? evento.evidenciaMovimientoCaducaEnMonotonicNanos() : 0L;
    }
}
