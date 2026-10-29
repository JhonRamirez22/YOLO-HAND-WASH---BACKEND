package com.handwash.config;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Finds every HTTP endpoint the cloud profile would expose, by scanning the real controllers. */
final class EndpointDiscovery {
    record Endpoint(String method, String path, String controller) {
        @Override public String toString() { return method + " " + path; }
        String concretePath() { return path.replaceAll("\\{[^}]+}", "x"); }
    }

    private EndpointDiscovery() {}

    static List<Endpoint> inCloudProfile() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        var environment = new org.springframework.core.env.StandardEnvironment();
        environment.setActiveProfiles("cloud"); // the scanner itself applies @Profile conditions against this
        scanner.setEnvironment(environment);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        TreeSet<String> seen = new TreeSet<>();
        List<Endpoint> endpoints = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.handwash")) {
            Class<?> type;
            try {
                type = Class.forName(definition.getBeanClassName());
            } catch (ClassNotFoundException missing) {
                throw new IllegalStateException(missing);
            }
            if (type.getSimpleName().endsWith("ProbeController")) continue; // test-only fixtures
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String[] prefixes = base == null || base.path().length == 0 ? new String[] {""} : base.path();
            for (Method method : type.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) continue;
                String[] paths = mapping.path().length == 0 ? new String[] {""} : mapping.path();
                RequestMethod[] verbs = mapping.method().length == 0 ? RequestMethod.values() : mapping.method();
                for (String prefix : prefixes) for (String path : paths) for (RequestMethod verb : verbs) {
                    String full = (prefix + path).isEmpty() ? "/" : prefix + path;
                    if (seen.add(verb + " " + full)) endpoints.add(new Endpoint(verb.name(), full, type.getSimpleName()));
                }
            }
        }
        return endpoints;
    }
}
