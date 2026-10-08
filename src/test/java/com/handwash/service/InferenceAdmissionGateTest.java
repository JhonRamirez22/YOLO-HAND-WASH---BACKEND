package com.handwash.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InferenceAdmissionGateTest {
    @Test
    void rejectsImmediatelyAtCapacityAndReleasesPermitExactlyOnce() {
        InferenceAdmissionGate gate = new InferenceAdmissionGate(1);
        InferenceAdmissionGate.Permit first = gate.tryAcquire();

        assertNotNull(first);
        assertNull(gate.tryAcquire());
        first.close();
        first.close();

        assertNotNull(gate.tryAcquire());
    }

    @Test
    void permitsConfiguredBoundedParallelism() {
        InferenceAdmissionGate gate = new InferenceAdmissionGate(2);
        InferenceAdmissionGate.Permit first = gate.tryAcquire();
        InferenceAdmissionGate.Permit second = gate.tryAcquire();

        assertNotNull(first);
        assertNotNull(second);
        assertNull(gate.tryAcquire());
        first.close();
        second.close();
        assertNotNull(gate.tryAcquire());
    }

    @Test
    void rejectsInvalidCapacityAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new InferenceAdmissionGate(0));
    }
}
