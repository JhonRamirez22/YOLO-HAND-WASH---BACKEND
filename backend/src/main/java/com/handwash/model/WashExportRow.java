package com.handwash.model;

import java.util.Map;

/**
 * A finished wash stripped of everything that could point to a person: no session id, no attempt number,
 * no photo reference. {@code steps} maps PASO_n names to VALIDADO/PENDIENTE/….
 */
public record WashExportRow(WashOutcome outcome, String reason, Long startedAtEpochMs, long finishedAtEpochMs,
                            Long durationMs, boolean slow, Map<String, String> steps) {}
