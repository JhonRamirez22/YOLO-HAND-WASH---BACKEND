package com.handwash.config;

import com.handwash.agent.EvaluadorSecuencia;
import com.handwash.agent.Notificador;
import com.handwash.agent.Receptor;
import com.handwash.agent.ValidadorReglas;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObserverPipelineConfig {

    private final Receptor receptor;
    private final EvaluadorSecuencia evaluadorSecuencia;
    private final ValidadorReglas validadorReglas;
    private final Notificador notificador;

    public ObserverPipelineConfig(
        Receptor receptor,
        EvaluadorSecuencia evaluadorSecuencia,
        ValidadorReglas validadorReglas,
        Notificador notificador
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
