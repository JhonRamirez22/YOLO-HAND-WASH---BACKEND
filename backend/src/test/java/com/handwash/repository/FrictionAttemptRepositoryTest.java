package com.handwash.repository;

import com.handwash.model.*;
import com.handwash.strategy.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import static org.junit.jupiter.api.Assertions.*;

class FrictionAttemptRepositoryTest {
    @Test void idempotentAtomicPersistenceAndRestartInterruption() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        var jdbc = new JdbcTemplate(ds);
        var repo = new FrictionAttemptRepository(jdbc);
        var a = new BalancedFrictionAttempt("test", new BalancedFrictionStrategy(new DomesticStrategy()),1500,650);
        a.observe(WashStep.PASO_1_PALMAS,1000,0);
        var record = a.pendingRecord();
        repo.save(record);
        repo.save(record);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM friction_attempt_validation", Integer.class));
        assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM friction_step_validation", Integer.class));
        assertEquals(1, repo.interruptAfterRestart());
        assertEquals(0, repo.interruptAfterRestart());
        assertEquals("BACKEND_RESTART", jdbc.queryForObject("SELECT reason FROM friction_attempt_validation", String.class));
        assertThrows(IllegalStateException.class, () -> repo.save(new FrictionAttemptRecord("test",1,record.policyVersion(),100,"ACTIVO",null,null,false,record.steps())));
    }
    @Test void failingStepWriteRollsBackEntireDecision() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        var jdbc = new JdbcTemplate(ds);
        var repo = new FrictionAttemptRepository(jdbc);
        var invalid = new FrictionEvaluation.Step("PASO_1_PALMAS", FrictionEvaluation.StepStatus.EN_CURSO, -1,6000,null);
        var record = new FrictionAttemptRecord("test",1,BalancedFrictionStrategy.VERSION,1,"ACTIVO",null,null,false,java.util.List.of(invalid));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> repo.save(record));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM friction_attempt_validation", Integer.class));
    }
    @Test void theQualityOfEachStepAndTheVerdictAreStored() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        var jdbc = new JdbcTemplate(ds);
        var repo = new FrictionAttemptRepository(jdbc);
        var a = new BalancedFrictionAttempt("test", new BalancedFrictionStrategy(new DomesticStrategy()), 1500, 650);
        long now = 1000;
        for (int step = 0; step < 6; step++) {
            var s = BalancedFrictionStrategy.STEPS.get(step);
            for (int i = 0; i <= 8; i++) { a.observe(s, now, 0, 0.9f); now += 1000; }
            if (step < 5) { a.observe(BalancedFrictionStrategy.STEPS.get(step + 1), now, 0, 0.9f); now += 200;
                a.observe(BalancedFrictionStrategy.STEPS.get(step + 1), now, 0, 0.9f); now += 200; }
        }
        var record = a.pendingRecord();
        assertNotNull(record.scorePct());
        repo.save(record);
        assertEquals(record.scorePct(), jdbc.queryForObject("SELECT score_pct FROM friction_attempt_validation", Integer.class));
        assertEquals(record.verdict(), jdbc.queryForObject("SELECT verdict FROM friction_attempt_validation", String.class));
        assertEquals(6, jdbc.queryForObject("SELECT COUNT(quality_pct) FROM friction_step_validation WHERE quality_pct > 0", Integer.class));
    }
}
