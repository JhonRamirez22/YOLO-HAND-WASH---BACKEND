package com.handwash.service;

import org.springframework.stereotype.Component;

/**
 * Single source of truth for whether a runtime may emit a clinical approval.
 * Development, non-clinical demo and supervised hospital-pilot profiles are
 * informational only. No current profile has a production clinical sign-off.
 * This is deliberately not environment-toggleable; a future production profile
 * must add an independently reviewed, signed authorization path.
 */
@Component
public final class ClinicalDecisionPolicy {
    public boolean isClinicalDecisionAllowed() {
        return false;
    }
}
