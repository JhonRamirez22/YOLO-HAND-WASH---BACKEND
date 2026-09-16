package com.handwash.strategy;

import com.handwash.model.ProtocolProfile;

/** Supplies the profile version in force when a session is created. */
@FunctionalInterface
public interface ProtocolProfileProvider {
    ProtocolProfile perfilVigente();

    static ProtocolProfileProvider predeterminado() {
        return () -> ProtocolProfile.PREDETERMINADO;
    }
}
