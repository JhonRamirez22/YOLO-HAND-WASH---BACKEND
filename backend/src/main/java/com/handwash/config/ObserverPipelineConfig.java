package com.handwash.config;

import com.handwash.agent.SequenceEvaluator;
import com.handwash.agent.Notifier;
import com.handwash.agent.DetectionReceiver;
import com.handwash.agent.SessionAuditObserver;
import com.handwash.agent.RuleValidator;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObserverPipelineConfig {

    private final DetectionReceiver receptor;
    private final SequenceEvaluator evaluadorSecuencia;
    private final RuleValidator validadorReglas;
    private final Notifier notificador;
    private final SessionAuditObserver auditObserver;

    public ObserverPipelineConfig(
        DetectionReceiver receptor,
        SequenceEvaluator evaluadorSecuencia,
        RuleValidator validadorReglas,
        Notifier notificador,
        SessionAuditObserver auditObserver
    ) {
        this.receptor = receptor;
        this.evaluadorSecuencia = evaluadorSecuencia;
        this.validadorReglas = validadorReglas;
        this.notificador = notificador;
        this.auditObserver = auditObserver;
    }

    @PostConstruct
    public void registerObserversOnce() {
        receptor.addCriticalObserver(evaluadorSecuencia);
        receptor.addCriticalObserver(validadorReglas);
        receptor.addObserver(notificador);
        receptor.addObserver(auditObserver);
    }
}
