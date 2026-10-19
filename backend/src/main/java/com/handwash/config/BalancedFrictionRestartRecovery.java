package com.handwash.config;

import com.handwash.repository.FrictionAttemptRepository;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/** Restart never reconstructs lost timing evidence, including local durable-database demos. */
@Component
public final class BalancedFrictionRestartRecovery implements SmartInitializingSingleton {
    private final FrictionAttemptRepository repository;
    public BalancedFrictionRestartRecovery(FrictionAttemptRepository repository) { this.repository = repository; }
    @Override public void afterSingletonsInstantiated() { repository.interruptAfterRestart(); }
}
