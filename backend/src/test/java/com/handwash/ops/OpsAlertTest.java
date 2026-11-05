package com.handwash.ops;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpsAlertTest {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(OpsAlertTest.class);

    @Test
    void writesTheExactTokenTheMetricFiltersMatch() {
        try (var capture = new LogCapture(OpsAlertTest.class)) {
            OpsAlert.raise(LOG, OpsAlert.Code.PRODUCER_SILENT, "silentSeconds=75");

            assertEquals(List.of("OPS_ALERT code=PRODUCER_SILENT silentSeconds=75"), capture.messages());
            assertEquals(1, capture.alerts(OpsAlert.Code.PRODUCER_SILENT));
            assertEquals(0, capture.alerts(OpsAlert.Code.AUDIT_CHAIN_BROKEN));
        }
    }

    @Test
    void neverCarriesControlCharactersOrLongText() {
        try (var capture = new LogCapture(OpsAlertTest.class)) {
            OpsAlert.raise(LOG, OpsAlert.Code.AUDIT_SEAL_FAILED, "x\nOPS_ALERT code=FORGED \"quoted\" " + "y".repeat(500));

            String message = capture.messages().get(0);
            assertFalse(message.contains("\n"));
            assertFalse(message.contains("\""));
            assertTrue(message.length() < 200);
            assertEquals(1, capture.messages().size());
            assertEquals(1, message.split("OPS_ALERT", -1).length - 1, "details cannot forge another alert token");
        }
    }

    @Test
    void everyCodeHasAStableNameUsedByTheAlarms() {
        assertEquals(List.of("FRICTION_PERSISTENCE_FAILED", "AUDIT_CHAIN_BROKEN", "PRODUCER_SILENT",
            "SHIFT_SESSION_START_FAILED", "PHOTO_UPLOAD_UNAVAILABLE", "AUDIT_SEAL_FAILED"),
            java.util.Arrays.stream(OpsAlert.Code.values()).map(Enum::name).toList());
    }

    private static final class List {
        static java.util.List<String> of(String... values) { return java.util.List.of(values); }
    }
}
