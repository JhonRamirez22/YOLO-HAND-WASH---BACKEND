package com.handwash.service;

import com.handwash.model.WashOutcome;
import com.handwash.model.WashExportRow;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WashReportAnalyticsTest {
    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    private static long at(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, BOGOTA).toInstant().toEpochMilli();
    }

    private static Map<String, String> steps(int validated) {
        Map<String, String> map = new LinkedHashMap<>();
        String[] names = {"PASO_1_PALMAS", "PASO_2_DORSOS", "PASO_3_INTERDIGITALES", "PASO_4_NUDILLOS", "PASO_5_PULGAR", "PASO_6_PUNTA_DE_DEDOS"};
        for (int i = 0; i < 6; i++) map.put(names[i], i < validated ? "VALIDADO" : "PENDIENTE");
        return map;
    }

    private static WashExportRow row(WashOutcome outcome, long finish, long durationMs, boolean slow, int validated, String reason) {
        return new WashExportRow(outcome, reason, finish - durationMs, finish, durationMs, slow, steps(validated));
    }

    @Test
    void shiftsFollowTheLocalClockIncludingTheMidnightWrap() {
        assertEquals(WashReportAnalytics.Turno.MANANA, WashReportAnalytics.turno(at(20, 6, 0), BOGOTA));
        assertEquals(WashReportAnalytics.Turno.MANANA, WashReportAnalytics.turno(at(20, 13, 59), BOGOTA));
        assertEquals(WashReportAnalytics.Turno.TARDE, WashReportAnalytics.turno(at(20, 14, 0), BOGOTA));
        assertEquals(WashReportAnalytics.Turno.TARDE, WashReportAnalytics.turno(at(20, 21, 59), BOGOTA));
        assertEquals(WashReportAnalytics.Turno.NOCHE, WashReportAnalytics.turno(at(20, 22, 0), BOGOTA));
        assertEquals(WashReportAnalytics.Turno.NOCHE, WashReportAnalytics.turno(at(21, 5, 59), BOGOTA));
    }

    @Test
    void ratesCountOnlyWashesAPersonCouldHaveDoneRightOrWrong() {
        var rows = List.of(
            row(WashOutcome.CORRECTO, at(20, 8, 0), 44_000, false, 6, null),
            row(WashOutcome.CORRECTO, at(20, 9, 0), 66_000, true, 6, null),
            row(WashOutcome.CORRECTO, at(20, 10, 0), 46_000, false, 6, null),
            row(WashOutcome.REPORTADO, at(20, 11, 0), 20_000, false, 2, "HANDS_ABSENT"),
            row(WashOutcome.INTERRUMPIDO, at(20, 12, 0), 5_000, false, 1, "SESSION_CLOSED"),
            row(WashOutcome.REINICIADO, at(20, 13, 0), 9_000, false, 3, "CONFIRMED_CONTAMINATION"));

        var total = WashReportAnalytics.tendencias(rows, BOGOTA).total();

        assertEquals(6, total.total());
        assertEquals(3, total.correctos());
        assertEquals(1, total.reportados());
        assertEquals(1, total.interrumpidos());
        assertEquals(1, total.reiniciados());
        assertEquals(1, total.lentos());
        assertEquals(75.0, total.porcentajeCorrectos(), "3 correct of 4 attributable washes; technical ends excluded");
        assertEquals(52_000L, total.duracionMediaCorrectosMs());
    }

    @Test
    void groupsByLocalDayAndShiftAndCountsWhichMovementsReportedWashesSkipped() {
        var rows = List.of(
            row(WashOutcome.CORRECTO, at(20, 9, 0), 44_000, false, 6, null),
            row(WashOutcome.REPORTADO, at(20, 15, 0), 20_000, false, 2, "HANDS_ABSENT"),
            row(WashOutcome.REPORTADO, at(21, 23, 0), 30_000, false, 4, "HANDS_ABSENT"),
            row(WashOutcome.CORRECTO, at(21, 7, 0), 45_000, false, 6, null));

        var trends = WashReportAnalytics.tendencias(rows, BOGOTA);

        assertEquals("America/Bogota", trends.zonaHoraria());
        assertEquals(2, trends.dias().size());
        assertEquals(2, trends.dias().get(0).metricas().total());
        assertEquals(2, trends.turnos().get(WashReportAnalytics.Turno.MANANA).correctos());
        assertEquals(1, trends.turnos().get(WashReportAnalytics.Turno.TARDE).reportados());
        assertEquals(1, trends.turnos().get(WashReportAnalytics.Turno.NOCHE).reportados());
        assertEquals(0L, trends.movimientosOmitidosEnReportados().get("PASO_1_PALMAS"));
        assertEquals(2L, trends.movimientosOmitidosEnReportados().get("PASO_6_PUNTA_DE_DEDOS"));
        assertEquals(1L, trends.movimientosOmitidosEnReportados().get("PASO_3_INTERDIGITALES"));
    }

    @Test
    void emptyDataGivesNullRatesInsteadOfAFalseHundredPercent() {
        var trends = WashReportAnalytics.tendencias(List.of(), BOGOTA);
        assertEquals(0, trends.total().total());
        assertNull(trends.total().porcentajeCorrectos());
        assertNull(trends.total().duracionMediaCorrectosMs());
        assertTrue(trends.dias().isEmpty());
    }

    @Test
    void csvIsAnonymousSpreadsheetSafeAndWellFormed() {
        var rows = List.of(
            row(WashOutcome.CORRECTO, at(20, 8, 30), 44_500, false, 6, null),
            row(WashOutcome.INTERRUMPIDO, at(20, 23, 10), 5_000, false, 1, "=cmd|' /C calc'!A0"));

        String csv = new String(WashReportAnalytics.csv(rows, BOGOTA), StandardCharsets.UTF_8);
        String[] lines = csv.split("\r\n");

        assertTrue(csv.startsWith("﻿"), "BOM so Excel reads accents");
        assertEquals("﻿fecha_local,hora_inicio_local,hora_fin_local,turno,resultado,motivo,duracion_s,lento,movimientos_validados,"
            + "paso_1_palmas,paso_2_dorsos,paso_3_interdigitales,paso_4_nudillos,paso_5_pulgar,paso_6_punta_de_dedos", lines[0]);
        assertEquals("2026-10-20,08:29:15,08:30:00,MANANA,CORRECTO,,44.5,false,6,VALIDADO,VALIDADO,VALIDADO,VALIDADO,VALIDADO,VALIDADO", lines[1]);
        assertTrue(lines[2].contains(",NOCHE,INTERRUMPIDO,\"'=cmd|' /C calc'!A0\","), "formula neutralized: " + lines[2]);
        assertFalse(csv.toLowerCase().contains("session"), "no session identifiers");
        assertEquals(3, lines.length);
    }

    @Test
    void cellEscapesQuotesCommasAndFormulaPrefixes() {
        assertEquals("", WashReportAnalytics.cell(null));
        assertEquals("HANDS_ABSENT", WashReportAnalytics.cell("HANDS_ABSENT"));
        assertEquals("\"a,b\"", WashReportAnalytics.cell("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", WashReportAnalytics.cell("say \"hi\""));
        assertEquals("\"'@SUM(1)\"", WashReportAnalytics.cell("@SUM(1)"));
        assertEquals("\"'+1\"", WashReportAnalytics.cell("+1"));
    }
}
