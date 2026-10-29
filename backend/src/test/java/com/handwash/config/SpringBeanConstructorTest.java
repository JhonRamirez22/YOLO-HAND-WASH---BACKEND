package com.handwash.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring cannot pick between several constructors unless exactly one is @Autowired (or there is a no-arg one);
 * it then fails at startup with "No default constructor found". Beans of the cloud profile are not created by the
 * H2 Spring tests, so this happened four times in a row, the last one taking the deployed service down. This scans every
 * bean class of every profile and fails the build before such a class can ship.
 */
class SpringBeanConstructorTest {
    private static List<Class<?>> beanClasses() throws Exception {
        TreeSet<String> names = new TreeSet<>();
        for (String[] profiles : new String[][] {{}, {"cloud"}, {"station"}, {"station-demo"}, {"diagnostics"}, {"local"}}) {
            var scanner = new ClassPathScanningCandidateComponentProvider(false);
            var environment = new StandardEnvironment();
            environment.setActiveProfiles(profiles);
            scanner.setEnvironment(environment);
            scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class)); // meta: Service, Repository, RestController, Configuration…
            for (BeanDefinition definition : scanner.findCandidateComponents("com.handwash")) names.add(definition.getBeanClassName());
        }
        List<Class<?>> classes = new ArrayList<>();
        for (String name : names) classes.add(Class.forName(name));
        return classes;
    }

    @Test
    void everyBeanClassHasOneUnambiguousConstructorForSpring() throws Exception {
        List<String> violations = new ArrayList<>();
        List<Class<?>> beans = beanClasses();
        for (Class<?> type : beans) {
            if (type.isInterface() || Modifier.isAbstract(type.getModifiers()) || type.getName().contains("Test")) continue;
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            if (constructors.length <= 1) continue;
            boolean autowired = false, noArg = false;
            for (Constructor<?> constructor : constructors) {
                autowired |= constructor.isAnnotationPresent(Autowired.class);
                noArg |= constructor.getParameterCount() == 0;
            }
            if (!autowired && !noArg) violations.add(type.getName() + " has " + constructors.length + " constructors and none is @Autowired");
        }
        assertTrue(beans.size() > 80, "the scan found the real beans: " + beans.size());
        assertTrue(violations.isEmpty(), "Spring would refuse to start with:\n" + String.join("\n", violations));
    }
}
