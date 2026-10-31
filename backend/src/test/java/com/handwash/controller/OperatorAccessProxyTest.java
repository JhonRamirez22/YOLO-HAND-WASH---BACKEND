package com.handwash.controller;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class OperatorAccessProxyTest {
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions {}

    @Test
    void cloudControllerCanBeCreatedWithRealTransactionAdvice() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("cloud");
            context.register(Transactions.class);
            context.registerBean(PlatformTransactionManager.class,
                () -> mock(PlatformTransactionManager.class));
            // Dependencies are unused: this regression checks proxy creation at startup.
            context.registerBean(OperatorAccessController.class,
                () -> new OperatorAccessController(null, null, null, null, null,
                    null, null, null, null, null, "test-client", null, null, null));
            context.refresh();
            assertTrue(AopUtils.isCglibProxy(context.getBean(OperatorAccessController.class)));
        }
    }
}
