package com.handwash.model;

import com.handwash.strategy.BalancedFrictionStrategy;
import com.handwash.strategy.DomesticStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Business invariants of WashSession that the first review of the class found unprotected. */
class WashSessionInvariantesTest {
    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    private WashSession frictionSession() {
        return new WashSession("inv-friction", ProtocolType.DOMESTICO,
            new BalancedFrictionStrategy(new DomesticStrategy()), 1_500L, 650L, false, nanos::get);
    }

    private void advanceMs(long ms) { nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(ms)); }

    @Test
    void closingAFrictionAttemptFreezesTheDurationAndClosingTwiceChangesNothing() {
        WashSession session = frictionSession();
        advanceMs(1_000L);
        session.closeFrictionAttempt();
        assertEquals(1_000L, session.getDuracionMs());

        advanceMs(60_000L);
        assertEquals(1_000L, session.getDuracionMs(), "el reloj no debe seguir corriendo tras el cierre");
        session.closeFrictionAttempt();
        assertEquals(1_000L, session.getDuracionMs());
        assertEquals(SessionStatus.EXPIRADA, session.getEstadoSesion());
    }

    @Test
    void anOmsActionOnAFrictionSessionIsRecordedByTheActiveEvaluator() {
        WashSession session = frictionSession();

        session.procesarAccionOms(WhoAction.values()[0], Map.of(), 1_000L, 0.9f);

        assertEquals(1, session.getHistorialInfracciones().size());
        assertEquals(InfractionType.MODO_DETECCION_INCOMPATIBLE, session.getHistorialInfracciones().get(0).getTipo());
    }

    @Test
    void aFrictionDetectionOnAnOmsSessionAnswersWithTheOmsView() {
        WashSession session = new WashSession("inv-oms", ProtocolType.DOMESTICO);
        ReflectionTestUtils.setField(session, "modoEvaluacion", "PROTOCOLO_OMS");

        WashStateResponse response = session.procesarDeteccion(WashStep.PASO_1_PALMAS, 1_000L);

        assertNotNull(response.getInfraccion(), "la respuesta debe reflejar la infracción del evaluador OMS");
        assertEquals(InfractionType.MODO_DETECCION_INCOMPATIBLE, response.getInfraccion().getTipo());
    }

    @Test
    void repeatedInfractionsAreDeduplicatedWithMonotonicTimeNotTheWallClock() {
        WashSession session = new WashSession("inv-dedupe", ProtocolType.DOMESTICO,
            new com.handwash.strategy.ValidationRuleStrategyFactory().crear(ProtocolType.DOMESTICO),
            1_500L, 650L, false, nanos::get);

        session.registrarInfraccion(InfractionType.PASO_OMITIDO, "detalle", WashStep.PASO_2_DORSOS);
        advanceMs(1_000L);
        session.registrarInfraccion(InfractionType.PASO_OMITIDO, "detalle", WashStep.PASO_2_DORSOS);
        assertEquals(1, session.getHistorialInfracciones().size());

        advanceMs(2_500L);
        session.registrarInfraccion(InfractionType.PASO_OMITIDO, "detalle", WashStep.PASO_2_DORSOS);
        assertEquals(2, session.getHistorialInfracciones().size());
    }

    @Test
    void theConstructorRejectsMissingCollaborators() {
        var strategy = new BalancedFrictionStrategy(new DomesticStrategy());
        assertThrows(NullPointerException.class, () -> new WashSession(null, ProtocolType.DOMESTICO, strategy));
        assertThrows(NullPointerException.class, () -> new WashSession("x", null, strategy));
        assertThrows(NullPointerException.class, () -> new WashSession("x", ProtocolType.DOMESTICO, null));
    }
}
