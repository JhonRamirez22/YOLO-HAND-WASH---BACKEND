package com.handwash.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ManagementExposureConfigurationTest {

    @Test
    void baseProfileDoesNotExposeMetrics() throws IOException {
        assertEquals(List.of("health"), exposedEndpoints("application.yml"));
    }

    @Test
    void localProfileExposesMetricsForDevelopmentDiagnostics() throws IOException {
        assertEquals(List.of("health", "metrics"), exposedEndpoints("application-local.yml"));
    }

    private List<String> exposedEndpoints(String filename) throws IOException {
        Path config = locateMainResource(filename);
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
            .load(filename, new FileSystemResource(config));
        ConfigurableEnvironment environment = new StandardEnvironment();
        sources.forEach(environment.getPropertySources()::addLast);

        return Binder.get(environment)
            .bind("management.endpoints.web.exposure.include", Bindable.listOf(String.class))
            .orElseThrow(() -> new IllegalStateException("No se configuró la exposición de Actuator"));
    }

    private Path locateMainResource(String filename) {
        for (Path candidate : List.of(
            Path.of("src/main/resources", filename),
            Path.of("backend/src/main/resources", filename))) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath();
            }
        }
        throw new IllegalStateException("No se encontró la configuración principal: " + filename);
    }
}
