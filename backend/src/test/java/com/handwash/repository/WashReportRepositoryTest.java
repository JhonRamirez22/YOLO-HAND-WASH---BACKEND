package com.handwash.repository;

import com.handwash.model.FrictionAttemptRecord;
import com.handwash.model.FrictionEvaluation;
import com.handwash.model.WashOutcome;
import com.handwash.strategy.BalancedFrictionStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WashReportRepositoryTest {
    private final JdbcTemplate jdbc;
    private final FrictionAttemptRepository repo;

    WashReportRepositoryTest() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        repo = new FrictionAttemptRepository(jdbc);
    }

    private static List<FrictionEvaluation.Step> steps(int validated) {
        return BalancedFrictionStrategy.STEPS.stream().map(step -> new FrictionEvaluation.Step(step.name(),
            step.getNumero() <= validated ? FrictionEvaluation.StepStatus.VALIDADO : FrictionEvaluation.StepStatus.PENDIENTE,
            step.getNumero() <= validated ? 7_000 : 0, 7_000, null)).toList();
    }

    private void finished(String session, WashOutcome outcome, String result, String reason,
                          int validated, long finishedAt, String evidence) {
        repo.save(new FrictionAttemptRecord(session, 1, BalancedFrictionStrategy.VERSION, 1, "ACTIVO",
            null, null, false, steps(0), finishedAt - 40_000, null, null, false, null, null));
        repo.save(new FrictionAttemptRecord(session, 1, BalancedFrictionStrategy.VERSION, 2, "INTERRUMPIDO",
            result, reason, true, steps(validated), finishedAt - 40_000, finishedAt, 40_000L,
            false, outcome, evidence));
    }

    @Test
    void listsFinishedWashesByDateAndOutcomeWithStepsAndPhotoStatus() {
        finished("good", WashOutcome.CORRECTO, "FRICTION_SEQUENCE_COMPLETE", null, 6, 2_000_000, null);
        finished("left", WashOutcome.REPORTADO, "INTERRUPTED", "HANDS_ABSENT", 2, 3_000_000, "DESACTIVADA");
        finished("old", WashOutcome.REPORTADO, "INTERRUPTED", "HANDS_ABSENT", 1, 500_000, "DESACTIVADA");

        var reported = repo.findReports(1_000_000, 4_000_000, WashOutcome.REPORTADO, 10);
        assertEquals(1, reported.size());
        var left = reported.get(0);
        assertEquals("left", left.sessionId());
        assertEquals(2_960_000L, left.inicioEpochMs());
        assertEquals(3_000_000L, left.finEpochMs());
        assertEquals(40_000L, left.duracionMs());
        assertEquals("DESACTIVADA", left.estadoEvidencia());
        assertEquals(2, left.pasos().stream().filter(s -> s.estado() == FrictionEvaluation.StepStatus.VALIDADO).count());

        var all = repo.findReports(1_000_000, 4_000_000, null, 10);
        assertEquals(List.of("left", "good"), all.stream().map(r -> r.sessionId()).toList(), "newest first");
        assertEquals(1, repo.findReports(1_000_000, 4_000_000, null, 1).size());
    }

    @Test
    void restartInterruptsActiveAttemptsWithoutReportingThePerson() {
        repo.save(new FrictionAttemptRecord("active", 1, BalancedFrictionStrategy.VERSION, 1, "ACTIVO",
            null, null, false, steps(1), 1_000L, null, null, false, null, null));
        assertEquals(1, repo.interruptAfterRestart());

        assertEquals("INTERRUMPIDO", jdbc.queryForObject(
            "SELECT outcome FROM friction_attempt_validation WHERE session_id='active'", String.class));
        assertEquals(1_000L, jdbc.queryForObject(
            "SELECT started_at_epoch_ms FROM friction_attempt_validation WHERE session_id='active'", Long.class));
        assertTrue(repo.findReports(0, Long.MAX_VALUE, WashOutcome.REPORTADO, 10).isEmpty());
    }

    @Test
    void exportRowsAreAnonymousOrderedByTimeAndCarryTheStepStates() {
        finished("good", WashOutcome.CORRECTO, "FRICTION_SEQUENCE_COMPLETE", null, 6, 2_000_000, null);
        finished("left", WashOutcome.REPORTADO, "INTERRUPTED", "HANDS_ABSENT", 2, 3_000_000, "DESACTIVADA");
        finished("old", WashOutcome.REPORTADO, "INTERRUPTED", "HANDS_ABSENT", 1, 500_000, "DESACTIVADA");

        var rows = repo.findExportRows(1_000_000, 4_000_000, null, 10);

        assertEquals(2, rows.size());
        assertEquals(2_000_000L, rows.get(0).finishedAtEpochMs(), "oldest first");
        assertEquals(WashOutcome.CORRECTO, rows.get(0).outcome());
        assertEquals(6, rows.get(0).steps().values().stream().filter("VALIDADO"::equals).count());
        assertEquals("HANDS_ABSENT", rows.get(1).reason());
        assertEquals(2, rows.get(1).steps().values().stream().filter("VALIDADO"::equals).count());
        assertEquals(1, repo.findExportRows(1_000_000, 4_000_000, WashOutcome.REPORTADO, 10).size());
        assertEquals(1, repo.findExportRows(1_000_000, 4_000_000, null, 1).size(), "the attempt limit applies");
        assertTrue(repo.findExportRows(5_000_000, 6_000_000, null, 10).isEmpty());
    }
}
