package com.handwash.repository;

import com.handwash.model.*;
import com.handwash.strategy.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in disposable local PostgreSQL only; migrates the schema itself (Flyway is idempotent). */
@EnabledIfEnvironmentVariable(named="HANDWASH_TEST_PG_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/handwash_test")
class FrictionPostgresIntegrationTest {
    @Test void postgresWritesAreAtomicIdempotentAndTerminalResultsImmutable() {
        var ds=new DriverManagerDataSource(System.getenv("HANDWASH_TEST_PG_URL"),"handwash_test","");
        // Idempotent: class order is not guaranteed, so this test must not rely on another class migrating first.
        org.flywaydb.core.Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        var jdbc=new JdbcTemplate(ds);
        var repo=new FrictionAttemptRepository(jdbc);
        String id=java.util.UUID.randomUUID().toString();
        var a=new BalancedFrictionAttempt(id,new BalancedFrictionStrategy(new DomesticStrategy()),1500,650);
        a.observe(WashStep.PASO_1_PALMAS,1000,0);
        var initial=a.pendingRecord();
        repo.save(initial); repo.save(initial); a.persisted(initial);
        assertEquals(6,jdbc.queryForObject("SELECT COUNT(*) FROM friction_step_validation WHERE session_id=?",Integer.class,id));
        a.interrupt("SESSION_CLOSED");
        var terminal=a.pendingRecord();
        repo.save(terminal);repo.save(terminal);
        assertEquals("INTERRUPTED",jdbc.queryForObject("SELECT result FROM friction_attempt_validation WHERE session_id=?",String.class,id));
        assertThrows(IllegalStateException.class,()->repo.save(new FrictionAttemptRecord(id,1,terminal.policyVersion(),terminal.revision()+1,"ACTIVO",null,null,false,terminal.steps())));
        String failingId=java.util.UUID.randomUUID().toString();
        var invalid=new FrictionEvaluation.Step("PASO_1_PALMAS",FrictionEvaluation.StepStatus.EN_CURSO,-1,6000,null);
        assertThrows(org.springframework.dao.DataAccessException.class,()->repo.save(new FrictionAttemptRecord(failingId,1,BalancedFrictionStrategy.VERSION,1,"ACTIVO",null,null,false,java.util.List.of(invalid))));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM friction_attempt_validation WHERE session_id=?",Integer.class,failingId));
    }
}
