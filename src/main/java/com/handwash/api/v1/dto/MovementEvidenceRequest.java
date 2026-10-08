package com.handwash.api.v1.dto;

/** Versioned wire shape for movement metadata; contains no domain behavior. */
public record MovementEvidenceRequest(Long secuencia, Integer manosVisibles,
                                      Double movimientoNormalizado,
                                      Boolean medicionValida, Long antiguedadMs,
                                      Double[][][] poseKeypoints, Double[][] handBoxes,
                                      Integer frameWidth, Integer frameHeight) {
    public MovementEvidenceRequest(Long secuencia, Integer manosVisibles,
                                   Double movimientoNormalizado, Boolean medicionValida,
                                   Long antiguedadMs) {
        this(secuencia, manosVisibles, movimientoNormalizado, medicionValida, antiguedadMs,
            null, null, null, null);
    }
}
