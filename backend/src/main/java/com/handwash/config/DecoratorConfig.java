package com.handwash.config;

import com.handwash.decorator.repository.SqlInjectionGuardFailedAttemptStoreDecorator;
import com.handwash.decorator.strategy.MetricsWashStrategyDecoratorFactory;
import com.handwash.repository.FailedAttemptRepository;
import com.handwash.repository.FailedAttemptStore;
import com.handwash.service.HandwashMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class DecoratorConfig {
    @Bean
    @Primary
    public FailedAttemptStore sqlInjectionGuardedFailedAttemptStore(FailedAttemptRepository jdbcStore) {
        return new SqlInjectionGuardFailedAttemptStoreDecorator(jdbcStore);
    }

    @Bean
    public MetricsWashStrategyDecoratorFactory fabricaDecoradorMetricasLavado(HandwashMetrics metrics) {
        return new MetricsWashStrategyDecoratorFactory(metrics);
    }
}
