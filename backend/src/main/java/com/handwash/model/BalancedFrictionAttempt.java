package com.handwash.model;

import com.handwash.strategy.BalancedFrictionStrategy;
import com.handwash.strategy.ValidationRuleStrategy;
import java.util.ArrayList;
import java.util.List;
import static com.handwash.model.FrictionEvaluation.StepStatus.*;

/** State belongs to one session and is accessed under that session's monitor. */
public final class BalancedFrictionAttempt {
    /** A wrong step held this long (not a flicker or a classifier doubt between two similar movements) sends the person back to the first step. */
    static final long SUSTAINED_WRONG_MS = 6_000L;
    /** How long the restart notice stays on screen before the next attempt starts. */
    static final long RESTART_NOTICE_MS = 4_000L;
    /** Going on to the next movement before finishing this one is guided, not punished, for this long. */
    static final long PREMATURE_GRACE_MS = 15_000L;
    /** Moving on after at least this share of the time asked counts the step, with the lower quality it earned. */
    static final double SHORT_ACCEPT_RATIO = 0.5;
    /** The wash is correct when the average quality of the six movements reaches this. */
    public static final int PASS_MARK_PCT = 80;
    /** Hands away this long after most of the last movement ends the wash. */
    static final long LAST_STEP_LEAVE_MS = 2_000L;
    static final String SEQUENCE_RESTART = "SEQUENCE_RESTART";
    private enum State { ESPERANDO_INICIO, ACTIVO, PAUSADO, COMPLETO_PARCIAL, INTERRUMPIDO }
    private final String sessionId;
    private final ValidationRuleStrategy strategy;
    private final long maxGapMs;
    private final long transitionGapMs;
    private final long[] credited = new long[6];
    /** Credited milliseconds weighted by how sure the detector was; divided by the credit it is the mean certainty. */
    private final double[] confidenceMs = new double[6];
    private final FrictionEvaluation.StepStatus[] statuses = new FrictionEvaluation.StepStatus[6];
    private int attempt = 1;
    private int index;
    private long revision;
    private long durableRevision;
    private long pendingRevision;
    private long lastObservation = -1;
    private long lastCredit = -1;
    private long lastPresence = -1;
    private int lastHands = -1;
    private long absentSince = -1;
    private long candidateAt = -1;
    /** Start of the current run of observations that are not the expected step; -1 when there is none. */
    private long wrongSince = -1;
    private long terminalAt = -1;
    private WashStep candidate;
    private State state = State.ESPERANDO_INICIO;
    private String reason = "WAITING_FOR_PALMS";
    private String result;
    private boolean terminal;
    private boolean blocked;
    private boolean expired;
    private FrictionEvaluation.Result lastResult;
    private final java.util.function.LongSupplier wallClock;
    private long startedMono = -1;
    private Long startedAtEpochMs;
    private Long finishedAtEpochMs;
    private Long durationMs;

    public BalancedFrictionAttempt(String sessionId, ValidationRuleStrategy strategy,
                                   long maxGapMs, long transitionGapMs) {
        this(sessionId, strategy, maxGapMs, transitionGapMs, System::currentTimeMillis);
    }

    public BalancedFrictionAttempt(String sessionId, ValidationRuleStrategy strategy,
                                   long maxGapMs, long transitionGapMs,
                                   java.util.function.LongSupplier wallClock) {
        this.wallClock = java.util.Objects.requireNonNull(wallClock);
        this.sessionId = sessionId;
        this.strategy = strategy;
        this.maxGapMs = maxGapMs;
        this.transitionGapMs = transitionGapMs;
        java.util.Arrays.fill(statuses, PENDIENTE);
    }

    public boolean started() { return state != State.ESPERANDO_INICIO; }
    public int number() { return attempt; }
    public WashStep expected() { return BalancedFrictionStrategy.STEPS.get(index); }
    public int validated() {
        int count = 0;
        for (var status : statuses) if (status == VALIDADO) count++;
        return count;
    }
    public long totalMs() { return java.util.Arrays.stream(credited).sum(); }
    public long currentMs() { return credited[index]; }
    /** UI-only candidate/presence changes must not look like replayed state or trigger DB writes. */
    public void refreshRevision() { revision++; }

    public void observe(WashStep step, long now, long initialCredit) {
        observe(step, now, initialCredit, 1.0f);
    }

    public void observe(WashStep step, long now, long initialCredit, float confidence) {
        double certainty = Float.isFinite(confidence) ? Math.max(0.0, Math.min(1.0, confidence)) : 0.0;
        if (now < 0 || now <= lastObservation || terminal || blocked || expired) return;
        long previousObservation = lastObservation;
        lastObservation = now;
        // Evidence that stopped for longer than the allowed gap proves nothing about how long a wrong step was held.
        if (previousObservation >= 0 && now - previousObservation > maxGapMs) wrongSince = -1;
        if (!BalancedFrictionStrategy.STEPS.contains(step)) { pause("NO_RELIABLE_STEP"); return; }
        if (!started()) {
            if (step != WashStep.PASO_1_PALMAS) return;
            startedMono = now;
            startedAtEpochMs = wallClock.getAsLong();
            state = State.ACTIVO;
            statuses[0] = EN_CURSO;
            credited[0] = Math.min(Math.max(0, initialCredit), strategy.getTiempoRequeridoPaso(step));
            confidenceMs[0] = credited[0] * certainty;
            significant();
        }
        if (step != expected()) {
            lastCredit = -1;
            // The legitimate next movement (current step validated) is not a mistake, however sparsely it is seen.
            boolean legitimateNext = statuses[index] == VALIDADO && index < 5
                && step == BalancedFrictionStrategy.STEPS.get(index + 1);
            boolean prematureNext = index < 5 && step == BalancedFrictionStrategy.STEPS.get(index + 1);
            if (legitimateNext) wrongSince = -1;
            else if (wrongSince < 0) wrongSince = now;
            long patience = prematureNext ? PREMATURE_GRACE_MS : SUSTAINED_WRONG_MS;
            if (wrongSince >= 0 && now - wrongSince >= patience) {
                // Held long enough to be a real mistake, not classifier noise: notify and start again from palms.
                finish("RESET_RISK", SEQUENCE_RESTART, State.INTERRUMPIDO, now, now);
                return;
            }
            boolean confirmed = step == candidate && now > candidateAt && now - candidateAt <= transitionGapMs;
            long candidateStart = candidateAt;
            candidate = step;
            candidateAt = now;
            if (!confirmed) return;
            boolean enoughToMoveOn = statuses[index] == VALIDADO || (prematureNext
                && credited[index] >= SHORT_ACCEPT_RATIO * strategy.getTiempoRequeridoPaso(expected()));
            if (enoughToMoveOn && index < 5 && step == BalancedFrictionStrategy.STEPS.get(index + 1)) {
                statuses[index] = VALIDADO;
                index++;
                statuses[index] = EN_CURSO;
                credited[index] = now - candidateStart;
                confidenceMs[index] = credited[index] * certainty;
                wrongSince = -1;
                state = State.ACTIVO;
                reason = null;
                candidate = null;
                lastCredit = now;
                significant();
                validateCurrent();
            } else {
                String rejection = index < 5 && step == BalancedFrictionStrategy.STEPS.get(index + 1)
                    ? "STEP_TOO_SHORT" : "STEP_OUT_OF_SEQUENCE";
                if (statuses[index] != VALIDADO) statuses[index] = REQUIERE_REPETICION;
                if (state != State.PAUSADO || !rejection.equals(reason)) {
                    state = State.PAUSADO;
                    reason = rejection;
                    significant();
                }
            }
            return;
        }
        candidate = null;
        candidateAt = -1;
        wrongSince = -1;
        if (state != State.ACTIVO || statuses[index] == REQUIERE_REPETICION) {
            state = State.ACTIVO;
            if (statuses[index] != VALIDADO) statuses[index] = EN_CURSO;
            reason = null;
            significant();
        }
        if (lastCredit >= 0 && now - lastCredit > 0 && now - lastCredit <= maxGapMs) {
            credited[index] += now - lastCredit;
            confidenceMs[index] += (now - lastCredit) * certainty;
            revision++;
        }
        lastCredit = now;
        validateCurrent();
    }

    private void validateCurrent() {
        if (!strategy.validarTiempoPaso(expected(), credited[index])) return;
        if (statuses[index] != VALIDADO) { statuses[index] = VALIDADO; significant(); }
        if (index == 5) finish("FRICTION_SEQUENCE_COMPLETE", null, State.COMPLETO_PARCIAL,
            lastObservation, lastObservation);
    }

    public void pause(String cause) {
        lastCredit = -1;
        candidate = null;
        candidateAt = -1;
        if (!started() || terminal || blocked || expired) return;
        if (state != State.PAUSADO || !java.util.Objects.equals(reason, cause)) {
            state = State.PAUSADO;
            reason = cause;
            significant();
        }
    }

    /** Only distinct, fresh server-received zero-hand pulses prove continuous absence. */
    public boolean presence(int hands, long now, long maxPresenceGapMs) {
        if (hands < 0 || hands > 2 || now < 0 || now <= lastPresence || expired) return false;
        long previous = lastPresence;
        lastPresence = now;
        revision++;
        lastHands = hands;
        if (hands != 0) absentSince = -1;
        else if (absentSince < 0 || previous < 0 || now - previous > maxPresenceGapMs) absentSince = now;
        if (hands != 2) pause("HANDS_NOT_OBSERVABLE");
        if (terminal && !pending() && ("HANDS_ABSENT".equals(reason)
            || "CONFIRMED_CONTAMINATION".equals(reason))) {
            nextAttempt();
            return true;
        }
        if (terminal && !pending() && SEQUENCE_RESTART.equals(reason) && hands == 2
            && terminalAt >= 0 && now - terminalAt >= RESTART_NOTICE_MS) {
            nextAttempt();
            return true;
        }
        if (hands != 0 || absentSince < 0) return false;
        if (!terminal && started() && index == 5 && now - absentSince >= LAST_STEP_LEAVE_MS
            && credited[5] >= SHORT_ACCEPT_RATIO * strategy.getTiempoRequeridoPaso(expected())) {
            // The person did most of the last movement and took the hands away: the wash is over, scored as done.
            statuses[5] = VALIDADO;
            finish("FRICTION_SEQUENCE_COMPLETE", null, State.COMPLETO_PARCIAL, absentSince, now);
            return false;
        }
        if (terminal && result.equals("FRICTION_SEQUENCE_COMPLETE")) {
            if (!pending() && now - absentSince >= 3_000) { nextAttempt(); return true; }
        } else if (!terminal && started() && now - absentSince >= 10_000) {
            // The wash ended when the hands left, not when absence was confirmed.
            finish("INTERRUPTED", "HANDS_ABSENT", State.INTERRUMPIDO, absentSince, now);
        }
        return false;
    }

    /** Replacing a producer invalidates temporal samples, not prior validated steps. */
    public void producerReplaced() {
        lastPresence = -1;
        absentSince = -1;
        pause("PRODUCER_REPLACED");
    }

    public void checkFreshness(long now, long maxPresenceGapMs) {
        boolean freshPresence = lastPresence >= 0 && now >= lastPresence && now - lastPresence <= maxPresenceGapMs;
        if (!freshPresence) absentSince = -1;
        if (lastObservation >= 0 && now > lastObservation && now - lastObservation > maxGapMs
            && (!freshPresence || lastHands == 2)) {
            pause(freshPresence ? "NO_FRESH_STEP_EVIDENCE" : "PRODUCER_UNREACHABLE");
        }
    }

    public void interrupt(String cause) {
        expired = true;
        if (started() && !terminal) finish("INTERRUPTED", cause, State.INTERRUMPIDO, lastObservation, -1);
    }

    /** Internal evidence boundary only; no public endpoint or pose heuristic invokes this. */
    public void confirmedContamination() {
        if (started() && !terminal) {
            finish("RESET_RISK", "CONFIRMED_CONTAMINATION", State.INTERRUMPIDO, lastObservation, -1);
        }
    }

    /**
     * @param endMono monotonic instant the wash actually ended
     * @param nowMono monotonic "now" for back-dating the wall clock, or -1 when unknown
     */
    private void finish(String outcome, String cause, State finalState, long endMono, long nowMono) {
        long wallNow = wallClock.getAsLong();
        finishedAtEpochMs = nowMono >= endMono && endMono >= 0 ? wallNow - (nowMono - endMono) : wallNow;
        durationMs = startedMono >= 0 && endMono >= startedMono ? endMono - startedMono : null;
        terminal = true;
        terminalAt = Math.max(0L, endMono);
        result = outcome;
        state = finalState;
        reason = cause;
        lastCredit = -1;
        significant();
    }

    private void nextAttempt() {
        attempt++;
        index = 0;
        java.util.Arrays.fill(credited, 0);
        java.util.Arrays.fill(confidenceMs, 0.0);
        java.util.Arrays.fill(statuses, PENDIENTE);
        terminal = false;
        result = null;
        state = State.ESPERANDO_INICIO;
        reason = "WAITING_FOR_PALMS";
        lastCredit = -1;
        absentSince = -1;
        candidate = null;
        wrongSince = -1;
        terminalAt = -1;
        startedMono = -1;
        startedAtEpochMs = null;
        finishedAtEpochMs = null;
        durationMs = null;
        revision++;
    }

    private void significant() { pendingRevision = ++revision; }
    public boolean pending() { return pendingRevision > durableRevision; }
    public FrictionAttemptRecord pendingRecord() {
        if (!pending()) return null;
        WashOutcome outcome = terminal ? WashOutcome.desde(result, reason) : null;
        long slowAfterMs = strategy.getUmbralLavadoLentoMs();
        boolean slow = outcome == WashOutcome.CORRECTO && slowAfterMs > 0
            && durationMs != null && durationMs > slowAfterMs;
        return new FrictionAttemptRecord(sessionId, attempt, strategy.getVersionPolitica(), pendingRevision,
            state.name(), result, reason, terminal, steps(), startedAtEpochMs, finishedAtEpochMs,
            durationMs, slow, outcome, null, terminal ? scorePct() : null, verdict());
    }
    public void persisted(FrictionAttemptRecord record) {
        if (record == null || record.attemptNumber() != attempt || record.revision() != pendingRevision) return;
        durableRevision = record.revision();
        if (terminal) lastResult = new FrictionEvaluation.Result(attempt, result, reason, record.steps(),
            record.scorePct() == null ? 0 : record.scorePct(), record.verdict());
        if (blocked) { blocked = false; lastCredit = -1; }
        revision++;
    }
    public void persistenceFailed() {
        if (!blocked) { blocked = true; lastCredit = -1; revision++; }
    }
    /** Time done against the time asked (at most all of it), times the mean certainty of the detector while doing it. */
    private int qualityPct(int i) {
        long required = strategy.getTiempoRequeridoPaso(BalancedFrictionStrategy.STEPS.get(i));
        if (required <= 0 || credited[i] <= 0) return 0;
        double coverage = Math.min(1.0, (double) credited[i] / required);
        double certainty = confidenceMs[i] / credited[i];
        return (int) Math.round(100.0 * coverage * Math.max(0.0, Math.min(1.0, certainty)));
    }

    /** Average quality of the six movements, the ones not done counting as zero. */
    public int scorePct() {
        int sum = 0;
        for (int i = 0; i < 6; i++) sum += qualityPct(i);
        return (int) Math.round(sum / 6.0);
    }

    private String verdict() {
        return terminal && "FRICTION_SEQUENCE_COMPLETE".equals(result)
            ? (scorePct() >= PASS_MARK_PCT ? "CORRECTO" : "RECHAZADO") : null;
    }

    private List<FrictionEvaluation.Step> steps() {
        List<FrictionEvaluation.Step> list = new ArrayList<>(6);
        for (int i = 0; i < 6; i++) {
            WashStep step = BalancedFrictionStrategy.STEPS.get(i);
            list.add(new FrictionEvaluation.Step(step.name(), statuses[i], credited[i],
                strategy.getTiempoRequeridoPaso(step), i == index ? reason : null, qualityPct(i)));
        }
        return List.copyOf(list);
    }
    public FrictionEvaluation snapshot() {
        boolean completed = terminal && "FRICTION_SEQUENCE_COMPLETE".equals(result) && !pending();
        return new FrictionEvaluation(strategy.getVersionPolitica(), revision, attempt,
            blocked || (terminal && pending()) ? "PENDIENTE_PERSISTENCIA" : state.name(),
            expected().name(), blocked ? "AUDIT_STORAGE_UNAVAILABLE" : reason, steps(),
            strategy.getDuracionMinimaFriccionMs(), strategy.getDuracionTotalMs(),
            completed, pending(), lastResult, guide(completed), scorePct(), verdict());
    }

    /** The single kiosk instruction for this snapshot; priority runs top to bottom. */
    private KioskGuide guide(boolean completed) {
        WashStep current = expected();
        long missing = Math.max(0L, strategy.getTiempoRequeridoPaso(current) - credited[index]);
        if (blocked || (terminal && pending())) return technical();
        if (terminal) {
            if (completed) return new KioskGuide(KioskGuide.Codigo.LAVADO_COMPLETO, null, null, KioskGuide.Tono.EXITO);
            if ("HANDS_ABSENT".equals(reason)) {
                return new KioskGuide(KioskGuide.Codigo.LAVADO_INCOMPLETO, current.name(), null, KioskGuide.Tono.ALERTA);
            }
            if ("CONFIRMED_CONTAMINATION".equals(reason) || SEQUENCE_RESTART.equals(reason)) {
                return new KioskGuide(KioskGuide.Codigo.REINICIA_LAVADO, WashStep.PASO_1_PALMAS.name(), null,
                    KioskGuide.Tono.ALERTA);
            }
            return technical();
        }
        if (!started()) {
            return new KioskGuide(KioskGuide.Codigo.INICIA_CON_PALMAS, WashStep.PASO_1_PALMAS.name(), null,
                KioskGuide.Tono.INFO);
        }
        if (state == State.PAUSADO) {
            switch (reason == null ? "" : reason) {
                case "HANDS_NOT_OBSERVABLE":
                    return new KioskGuide(KioskGuide.Codigo.MUESTRA_AMBAS_MANOS, null, null,
                        KioskGuide.Tono.CORRECCION);
                case "STEP_TOO_SHORT":
                    return new KioskGuide(KioskGuide.Codigo.VUELVE_A_PASO, current.name(), missing,
                        KioskGuide.Tono.CORRECCION);
                case "STEP_OUT_OF_SEQUENCE":
                    return new KioskGuide(KioskGuide.Codigo.SIGUE_EL_ORDEN, current.name(), missing,
                        KioskGuide.Tono.CORRECCION);
                case "PRODUCER_UNREACHABLE", "PRODUCER_REPLACED":
                    return technical();
                default:
                    break;
            }
        }
        if (statuses[index] == VALIDADO && index < 5) {
            return new KioskGuide(KioskGuide.Codigo.CAMBIA_A_PASO,
                BalancedFrictionStrategy.STEPS.get(index + 1).name(), null, KioskGuide.Tono.INFO);
        }
        if (state == State.PAUSADO) {
            return new KioskGuide(KioskGuide.Codigo.MANTEN_EL_MOVIMIENTO, current.name(), missing,
                KioskGuide.Tono.CORRECCION);
        }
        return new KioskGuide(KioskGuide.Codigo.CONTINUA_PASO, current.name(), missing, KioskGuide.Tono.INFO);
    }

    private static KioskGuide technical() {
        return new KioskGuide(KioskGuide.Codigo.ESPERA_TECNICA, null, null, KioskGuide.Tono.TECNICO);
    }
}
