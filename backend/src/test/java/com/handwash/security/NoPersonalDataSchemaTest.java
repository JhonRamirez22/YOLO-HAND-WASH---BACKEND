package com.handwash.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-08 / R-09: the records about washes are anonymous and hold no media. If someone adds a column that could
 * identify the person who washed, or a binary column, this test fails and forces a conscious decision.
 */
class NoPersonalDataSchemaTest {
    private static final Set<String> WASH_TABLES = Set.of("WASH_ENCOUNTERS", "WASH_AUDIT_EVENTS", "FRICTION_ATTEMPT_VALIDATION",
        "FRICTION_STEP_VALIDATION", "FAILED_ATTEMPTS", "WASH_REPORT_EVIDENCE", "AUDIT_SEALS");
    private static final Pattern IDENTITY = Pattern.compile("(?i)(^|_)(name|email|mail|phone|telefono|document|cedula|dni|passport|"
        + "face|rostro|employee|empleado|patient|paciente|badge|nfc|rfid|username|user_id)(_|$)");
    /** Staff accounts (operators/admins) are referenced by their opaque Cognito subject, never by name. */
    private static final Set<String> ALLOWED = Set.of("ACTOR_SUBJECT");
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        jdbc = new JdbcTemplate(database);
    }

    @AfterEach
    void tearDown() { database.shutdown(); }

    @Test
    void washTablesCarryNoColumnThatCouldIdentifyThePersonWhoWashed() {
        List<String> offenders = new ArrayList<>();
        var columns = jdbc.queryForList("SELECT TABLE_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'PUBLIC'");
        long seen = 0;
        for (var column : columns) {
            String table = String.valueOf(column.get("TABLE_NAME")), name = String.valueOf(column.get("COLUMN_NAME"));
            if (!WASH_TABLES.contains(table)) continue;
            seen++;
            if (IDENTITY.matcher(name).find() && !ALLOWED.contains(name)) offenders.add(table + "." + name);
        }
        assertTrue(seen > 40, "the wash tables were inspected: " + seen);
        assertTrue(offenders.isEmpty(), "Possible personal-data columns: " + offenders);
    }

    @Test
    void noTableStoresBinaryMediaInTheDatabase() {
        var binary = jdbc.queryForList("""
            SELECT TABLE_NAME || '.' || COLUMN_NAME AS col FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = 'PUBLIC' AND DATA_TYPE IN ('BINARY LARGE OBJECT', 'BINARY VARYING', 'BINARY', 'CHARACTER LARGE OBJECT')
              AND TABLE_NAME NOT IN ('FAILED_ATTEMPTS')""", String.class);
        assertTrue(binary.isEmpty(), "Binary or large-object columns found (photos live in the encrypted bucket only): " + binary);
    }
}
