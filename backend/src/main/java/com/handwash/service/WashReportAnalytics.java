package com.handwash.service;

import com.handwash.model.WashStep;
import com.handwash.model.ProtocolProfile;
import com.handwash.model.WashOutcome;
import com.handwash.model.WashExportRow;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure statistics and CSV for the infection-control view. Rates use only washes a person could have done
 * right or wrong (CORRECTO vs REPORTADO): technical interruptions and restarts are shown but never counted
 * against anybody (R-02).
 */
public final class WashReportAnalytics {
    public enum Turno {
        MANANA("06:00-14:00"), TARDE("14:00-22:00"), NOCHE("22:00-06:00");
        private final String horario;
        Turno(String horario) { this.horario = horario; }
        public String horario() { return horario; }
    }

    public record Bucket(long total, long correctos, long reportados, long reiniciados, long interrumpidos,
                         long lentos, Long duracionMediaCorrectosMs, Double porcentajeCorrectos) {}
    public record Dia(LocalDate fecha, Bucket metricas) {}
    public record Tendencias(String zonaHoraria, Bucket total, List<Dia> dias, Map<Turno, Bucket> turnos,
                             Map<String, Long> movimientosOmitidosEnReportados) {}

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String BOM = "﻿";

    private WashReportAnalytics() {}

    public static Turno turno(long epochMs, ZoneId zone) {
        LocalTime time = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime();
        if (!time.isBefore(LocalTime.of(6, 0)) && time.isBefore(LocalTime.of(14, 0))) return Turno.MANANA;
        if (!time.isBefore(LocalTime.of(14, 0)) && time.isBefore(LocalTime.of(22, 0))) return Turno.TARDE;
        return Turno.NOCHE;
    }

    public static Tendencias tendencias(List<WashExportRow> rows, ZoneId zone) {
        Map<LocalDate, List<WashExportRow>> byDay = new TreeMap<>();
        Map<Turno, List<WashExportRow>> byShift = new EnumMap<>(Turno.class);
        for (Turno turno : Turno.values()) byShift.put(turno, new java.util.ArrayList<>());
        Map<String, Long> omitted = new java.util.LinkedHashMap<>();
        for (WashStep paso : ProtocolProfile.PASOS) omitted.put(paso.name(), 0L);
        for (WashExportRow row : rows) {
            byDay.computeIfAbsent(Instant.ofEpochMilli(row.finishedAtEpochMs()).atZone(zone).toLocalDate(), d -> new java.util.ArrayList<>()).add(row);
            byShift.get(turno(row.finishedAtEpochMs(), zone)).add(row);
            if (row.outcome() == WashOutcome.REPORTADO) {
                row.steps().forEach((step, status) -> {
                    if (!"VALIDADO".equals(status) && omitted.containsKey(step)) omitted.merge(step, 1L, Long::sum);
                });
            }
        }
        List<Dia> dias = byDay.entrySet().stream().map(e -> new Dia(e.getKey(), bucket(e.getValue()))).toList();
        Map<Turno, Bucket> turnos = new EnumMap<>(Turno.class);
        byShift.forEach((turno, list) -> turnos.put(turno, bucket(list)));
        return new Tendencias(zone.getId(), bucket(rows), dias, turnos, omitted);
    }

    static Bucket bucket(List<WashExportRow> rows) {
        long correct = count(rows, WashOutcome.CORRECTO), reported = count(rows, WashOutcome.REPORTADO);
        long slow = rows.stream().filter(r -> r.outcome() == WashOutcome.CORRECTO && r.slow()).count();
        var durations = rows.stream().filter(r -> r.outcome() == WashOutcome.CORRECTO && r.durationMs() != null)
            .mapToLong(WashExportRow::durationMs).toArray();
        Long mean = durations.length == 0 ? null : Math.round(java.util.Arrays.stream(durations).average().orElse(0));
        Double rate = correct + reported == 0 ? null : Math.round(1000.0 * correct / (correct + reported)) / 10.0;
        return new Bucket(rows.size(), correct, reported, count(rows, WashOutcome.REINICIADO),
            count(rows, WashOutcome.INTERRUMPIDO), slow, mean, rate);
    }

    private static long count(List<WashExportRow> rows, WashOutcome outcome) {
        return rows.stream().filter(r -> r.outcome() == outcome).count();
    }

    /** UTF-8 CSV with BOM (opens correctly in Excel). Anonymous: no ids, no photo state. */
    public static byte[] csv(List<WashExportRow> rows, ZoneId zone) {
        StringBuilder out = new StringBuilder(BOM);
        out.append("fecha_local,hora_inicio_local,hora_fin_local,turno,resultado,motivo,duracion_s,lento,movimientos_validados");
        for (WashStep paso : ProtocolProfile.PASOS) out.append(',').append(paso.name().toLowerCase(Locale.ROOT));
        out.append("\r\n");
        for (WashExportRow row : rows) {
            ZonedDateTime end = Instant.ofEpochMilli(row.finishedAtEpochMs()).atZone(zone);
            long validated = row.steps().values().stream().filter("VALIDADO"::equals).count();
            out.append(end.format(DATE)).append(',')
                .append(row.startedAtEpochMs() == null ? "" : Instant.ofEpochMilli(row.startedAtEpochMs()).atZone(zone).format(TIME)).append(',')
                .append(end.format(TIME)).append(',').append(turno(row.finishedAtEpochMs(), zone).name()).append(',')
                .append(row.outcome().name()).append(',').append(cell(row.reason())).append(',')
                .append(row.durationMs() == null ? "" : String.format(Locale.ROOT, "%.1f", row.durationMs() / 1000.0)).append(',')
                .append(row.slow()).append(',').append(validated);
            for (WashStep paso : ProtocolProfile.PASOS) out.append(',').append(cell(row.steps().getOrDefault(paso.name(), "")));
            out.append("\r\n");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Values here are codes, but a spreadsheet would execute anything starting with = + - @ so it is neutralized. */
    static String cell(String value) {
        if (value == null || value.isEmpty()) return "";
        String safe = value.replace("\"", "\"\"");
        if ("=+-@\t\r".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        return safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.startsWith("'") ? "\"" + safe + "\"" : safe;
    }
}
