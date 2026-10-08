package com.handwash.observer;

import com.handwash.model.DetectionEvent;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class Subject {
    private static final Logger LOG = Logger.getLogger(Subject.class.getName());
    /** Critical subscribers run first; optional subscribers never delay State/Strategy. */
    private final CopyOnWriteArrayList<DetectionObserver> observers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<DetectionObserver> criticalObservers = new CopyOnWriteArrayList<>();

    public synchronized void addObserver(DetectionObserver observer) {
        if (observer != null && !criticalObservers.contains(observer)) {
            observers.addIfAbsent(observer);
        }
    }

    public synchronized void removeObserver(DetectionObserver observer) {
        observers.remove(observer);
        criticalObservers.remove(observer);
    }

    public synchronized void addCriticalObserver(DetectionObserver observer) {
        if (observer == null) return;
        observers.remove(observer);
        criticalObservers.addIfAbsent(observer);
    }

    public void notifyObservers(DetectionEvent evento) {
        // La salida nunca debe publicarse antes de completar State y Strategy.
        for (DetectionObserver observer : criticalObservers) {
            try {
                observer.onDeteccion(evento);
            } catch (RuntimeException exception) {
                LOG.log(Level.SEVERE, "Observer fallo al procesar detección", exception);
                throw new DetectionPipelineException("Fallo en etapa crítica de validación", exception);
            }
        }
        for (DetectionObserver observer : observers) {
            try {
                observer.onDeteccion(evento);
            } catch (RuntimeException exception) {
                LOG.log(Level.SEVERE, "Observer opcional fallo al procesar detección", exception);
            }
        }
    }

    /** Notifies optional observers that session state changed without an accepted detection. */
    public void notifyStateChanged(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        for (DetectionObserver observer : observers) {
            try {
                observer.onStateChanged(sessionId);
            } catch (RuntimeException exception) {
                LOG.log(Level.WARNING, "Observer opcional fallo al actualizar estado", exception);
            }
        }
    }

    public int getObserverCount() {
        return observers.size() + criticalObservers.size();
    }
}
