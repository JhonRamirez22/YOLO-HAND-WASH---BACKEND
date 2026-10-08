package com.handwash.observer;

import com.handwash.agent.Receiver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SubjectTest {

    @Test
    void doesNotRegisterDuplicateObservers() {
        Receiver receptor = new Receiver();
        DetectionObserver observer = evento -> {};

        receptor.addObserver(observer);
        receptor.addObserver(observer);

        assertEquals(1, receptor.getObserverCount());
    }

    @Test
    void promotesAnObserverToCriticalWithoutDuplicatingIt() {
        Receiver receptor = new Receiver();
        DetectionObserver observer = evento -> {};

        receptor.addObserver(observer);
        receptor.addCriticalObserver(observer);
        receptor.addObserver(observer);

        assertEquals(1, receptor.getObserverCount());
    }

    @Test
    void criticalObserversAlwaysRunBeforeOptionalObservers() {
        Receiver receptor = new Receiver();
        StringBuilder order = new StringBuilder();
        receptor.addObserver(evento -> order.append("optional;"));
        receptor.addCriticalObserver(evento -> order.append("critical;"));

        receptor.recibir("s1", "Paso1_Palmas", 0.9f);

        assertEquals("critical;optional;", order.toString());
    }

    @Test
    void oneObserverFailureDoesNotStopThePipeline() {
        Receiver receptor = new Receiver();
        int[] notifications = {0};
        receptor.addObserver(evento -> { throw new IllegalStateException("simulated consumer failure"); });
        receptor.addObserver(evento -> notifications[0]++);

        assertDoesNotThrow(() -> receptor.recibir("s1", "Paso1_Palmas", 0.9f));
        assertEquals(1, notifications[0]);
    }

    @Test
    void criticalObserverFailureStopsLaterStages() {
        Receiver receptor = new Receiver();
        int[] notifications = {0};
        receptor.addObserver(evento -> notifications[0]++);
        receptor.addCriticalObserver(evento -> { throw new IllegalStateException("validator failed"); });

        assertThrows(DetectionPipelineException.class,
            () -> receptor.recibir("s1", "Paso1_Palmas", 0.9f));
        assertEquals(0, notifications[0]);
    }
}
