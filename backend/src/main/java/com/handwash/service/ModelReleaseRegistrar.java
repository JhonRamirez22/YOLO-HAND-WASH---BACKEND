package com.handwash.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/** Records the model release this deployment declares, so a new model shows up as «sin evidencia» immediately. */
@Component
@Profile("cloud")
public class ModelReleaseRegistrar {
    private static final Logger log = LoggerFactory.getLogger(ModelReleaseRegistrar.class);
    private final ChangeControlService control;

    public ModelReleaseRegistrar(ChangeControlService control) { this.control = control; }

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        try {
            control.registerConfiguredRelease();
        } catch (DataAccessException unavailable) {
            log.warn("Model release registration postponed: {}", unavailable.getClass().getSimpleName());
        }
    }
}
