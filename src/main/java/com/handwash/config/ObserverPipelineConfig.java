package com.handwash.config;

import com.handwash.agent.SequenceEvaluator;
import com.handwash.agent.Notifier;
import com.handwash.agent.Receiver;
import com.handwash.agent.RuleValidator;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObserverPipelineConfig {

    private final Receiver receptor;
    private final SequenceEvaluator evaluadorSecuencia;
    private final RuleValidator validadorReglas;
    private final Notifier notificador;

    public ObserverPipelineConfig(
        Receiver receptor,
        SequenceEvaluator evaluadorSecuencia,
        RuleValidator validadorReglas,
        Notifier notificador
    ) {
        this.receptor = receptor;
        this.evaluadorSecuencia = evaluadorSecuencia;
        this.validadorReglas = validadorReglas;
        this.notificador = notificador;
    }

    @PostConstruct
    public void registerObserversOnce() {
        receptor.addCriticalObserver(evaluadorSecuencia);
        receptor.addCriticalObserver(validadorReglas);
        receptor.addObserver(notificador);
    }
}
