package com.handwash.api.v1.dto;

import com.handwash.model.ProfileChange;

/** One profile version with its reason, state and (when it exists) the validation evidence linked to it. */
public record ProfileChangeResponse(ProtocolProfileResponse perfil, String motivo, Integer revertidoDe, String estado,
                                   String decididoPor, Long decididoEnEpochMs, boolean conInformeDeValidacion) {
    public static ProfileChangeResponse of(ProfileChange cambio, boolean withEvidence) {
        return new ProfileChangeResponse(ProtocolProfileResponse.of(cambio.perfil()), cambio.motivo(), cambio.revertidoDe(),
            cambio.estado(), cambio.decididoPor(), cambio.decididoEnEpochMs(), withEvidence);
    }
}
