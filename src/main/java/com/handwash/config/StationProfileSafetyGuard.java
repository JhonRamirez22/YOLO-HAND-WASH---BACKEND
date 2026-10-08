package com.handwash.config;

import com.handwash.intention.CadenaIntencionLavado;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.CommandLinePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Enforces single-sink local safety in hospital and explicitly non-clinical demo profiles. */
@Component
@Profile({"station", "station-demo"})
public final class StationProfileSafetyGuard implements BeanFactoryPostProcessor, EnvironmentAware {
    private static final Set<String> APPROVED_STATION_ENVIRONMENT_OVERRIDES = Set.of(
        "SPRING_PROFILES_ACTIVE",
        "SERVER_PORT",
        "SERVER_ADDRESS",
        "HANDWASH_CORS_ALLOWED_ORIGINS",
        "HANDWASH_OMS_MODEL_READY",
        "HANDWASH_OMS_INPUT_ENABLED",
        "HANDWASH_INFERENCE_API_ENABLED",
        "HANDWASH_PRODUCER_REQUIRE_V2",
        "HANDWASH_SESSION_ACCESS_REQUIRED",
        "HANDWASH_SESSION_MAX_ACTIVE_SESSIONS",
        "HANDWASH_RELEASE_PUBLIC_KEY_PATH"
    );
    private static final Set<String> JVM_OPTION_ENVIRONMENT_VARIABLES = Set.of(
        "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS");

    /** Current reviewed station policy; any change requires a new signed release and evidence. */
    private static final Map<String, String> LOCKED_DECISION_POLICY = Map.ofEntries(
        Map.entry("handwash.receptor.confidence-threshold", "0.35"),
        Map.entry("handwash.receptor.oms-confidence-threshold", "0.6"),
        Map.entry("handwash.intention.confirmation-ms", "650"),
        Map.entry("handwash.intention.max-gap-ms", "650"),
        Map.entry("handwash.intention.min-observations", "3"),
        Map.entry("handwash.intention.pause-reset-ms", "1500"),
        Map.entry("handwash.intention.start-confidence", "0.75"),
        Map.entry("handwash.intention.hand-presence-warmup-ms", "3000"),
        Map.entry("handwash.intention.hand-presence-max-gap-ms", "500"),
        Map.entry("handwash.session.timeout-minutes", "5"),
        Map.entry("handwash.session.terminal-retention-minutes", "30"),
        Map.entry("handwash.session.max-detection-gap-ms", "1500"),
        Map.entry("handwash.session.step-transition-confirm-max-gap-ms", "650"),
        Map.entry("handwash.session.max-sessions", "128"),
        Map.entry("handwash.session.access-token-ttl-ms", "86400000")
    );

    /** Bounds local expiry, abuse protection and dashboard delivery in station profiles. */
    private static final Map<String, String> LOCKED_OPERATIONAL_POLICY = Map.ofEntries(
        Map.entry("server.address", "127.0.0.1"),
        Map.entry("server.port", "8080"),
        Map.entry("spring.web.resources.add-mappings", "true"),
        Map.entry("spring.task.scheduling.pool.size", "4"),
        Map.entry("server.tomcat.threads.max", "32"),
        Map.entry("server.tomcat.threads.min-spare", "4"),
        Map.entry("server.tomcat.accept-count", "16"),
        Map.entry("server.tomcat.max-connections", "512"),
        Map.entry("handwash.security.rate-limit-window-ms", "60000"),
        Map.entry("handwash.security.session-create-per-window", "20"),
        Map.entry("handwash.security.session-pair-per-window", "10"),
        Map.entry("handwash.security.rate-limit-max-clients", "10000"),
        Map.entry("handwash.security.rate-limit-cleanup-ms", "60000"),
        Map.entry("handwash.session.expiration-check-ms", "1000"),
        Map.entry("handwash.session.failed-attempt-persist-ms", "500"),
        Map.entry("handwash.session.cleanup-check-ms", "60000"),
        Map.entry("handwash.notification.flush-ms", "33"),
        Map.entry("handwash.notification.send-timeout-ms", "3000"),
        Map.entry("handwash.notification.max-websocket-clients", "256"),
        Map.entry("handwash.notification.expired-summary-recovery-ms", "10000")
    );

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        validateStationStartup(environment,
            Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize());
    }

    void validateStationStartup(Environment configuredEnvironment, Path projectRoot) {
        boolean hospitalStation = java.util.Arrays.stream(configuredEnvironment.getActiveProfiles())
            .anyMatch("station"::equals);
        boolean demoStation = java.util.Arrays.stream(configuredEnvironment.getActiveProfiles())
            .anyMatch("station-demo"::equals);
        validateExternalOverrides(configuredEnvironment);
        if (hospitalStation && demoStation) {
            throw unsafe("no se pueden activar simultáneamente los perfiles station y station-demo.");
        }
        if ((hospitalStation || demoStation) && configuredEnvironment.getActiveProfiles().length != 1) {
            throw unsafe("station y station-demo no pueden combinarse con otros perfiles Spring.");
        }
        validateConfiguration(configuredEnvironment, projectRoot);
        if (hospitalStation) {
            String publicKeyPath = configuredEnvironment.getProperty("HANDWASH_RELEASE_PUBLIC_KEY_PATH");
            File source = new ApplicationHome(StationProfileSafetyGuard.class).getSource();
            Path runningArtifact = source == null ? null : source.toPath();
            StationReleaseManifestVerifier.requireAuthorizedRelease(
                projectRoot, publicKeyPath, runningArtifact,
                configuredEnvironment.getProperty(
                    "handwash.intention.start-minimum-normalized-movement", Double.class,
                    CadenaIntencionLavado.DEFAULT_MOVEMENT_THRESHOLD),
                configuredEnvironment.getProperty(
                    "handwash.intention.step-minimum-normalized-movement", Double.class,
                    CadenaIntencionLavado.DEFAULT_MOVEMENT_THRESHOLD));
            validateAuthorizedHospitalConfiguration(configuredEnvironment);
            secureRuntimeDirectory(projectRoot);
        } else if (demoStation) {
            System.getLogger(StationProfileSafetyGuard.class.getName()).log(
                System.Logger.Level.WARNING,
                "Perfil station-demo activo: uso exclusivamente interno/no clínico; release no verificado.");
            secureRuntimeDirectory(projectRoot);
        } else {
            throw unsafe("el guard solo se puede usar con el perfil station o station-demo.");
        }
    }

    /**
     * Station launch is allowlisted by the supervisor. Enforce the same
     * boundary in Java so direct launches cannot inject extra Spring policy
     * through CLI arguments, JSON, JVM properties, or external config files.
     */
    static void validateExternalOverrides(Environment configuredEnvironment) {
        if (!(configuredEnvironment instanceof ConfigurableEnvironment configurable)) {
            throw unsafe("no se pudo inspeccionar el origen de configuración de la estación.");
        }
        for (PropertySource<?> source : configurable.getPropertySources()) {
            String sourceName = source.getName().toLowerCase(Locale.ROOT);
            if (source instanceof CommandLinePropertySource<?> commandLine
                && commandLine.getPropertyNames().length > 0) {
                throw unsafe("no se permiten argumentos Spring adicionales al iniciar la estación.");
            }
            if ("spring.application.json".equals(sourceName)) {
                throw unsafe("SPRING_APPLICATION_JSON no está permitido en la estación.");
            }
            if (source instanceof SystemEnvironmentPropertySource systemEnvironment) {
                for (String propertyName : systemEnvironment.getPropertyNames()) {
                    if (isStationOverrideEnvironmentVariable(propertyName)
                        && !APPROVED_STATION_ENVIRONMENT_OVERRIDES.contains(propertyName)) {
                        throw unsafe("la variable de entorno " + propertyName
                            + " no pertenece al allowlist de estación.");
                    }
                }
            }
            if ("systemproperties".equals(sourceName)
                && source instanceof EnumerablePropertySource<?> systemProperties) {
                for (String propertyName : systemProperties.getPropertyNames()) {
                    if (isSpringConfigurationProperty(propertyName)) {
                        throw unsafe("la propiedad JVM " + propertyName
                            + " no puede sobrescribir la configuración de estación.");
                    }
                }
            }
            if (isExternalConfigSource(sourceName)) {
                throw unsafe("no se permiten archivos externos de configuración Spring: "
                    + source.getName() + ".");
            }
        }
    }

    private static boolean isStationOverrideEnvironmentVariable(String name) {
        return name.startsWith("SPRING_") || name.startsWith("SERVER_")
            || name.startsWith("HANDWASH_") || name.startsWith("MANAGEMENT_")
            || name.startsWith("LOGGING_") || JVM_OPTION_ENVIRONMENT_VARIABLES.contains(name);
    }

    private static boolean isSpringConfigurationProperty(String name) {
        return name.startsWith("spring.") || name.startsWith("server.")
            || name.startsWith("handwash.") || name.startsWith("management.")
            || name.startsWith("logging.");
    }

    private static boolean isExternalConfigSource(String sourceName) {
        if (sourceName.contains("file:") || sourceName.contains("file [")
            || sourceName.contains("configtree:") || sourceName.contains("config tree")) return true;
        return sourceName.contains("config resource")
            && !sourceName.contains("class path resource")
            && !sourceName.contains("classpath:");
    }

    void validateConfiguration(Environment configuredEnvironment) {
        validateConfiguration(configuredEnvironment,
            Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize());
    }

    void validateConfiguration(Environment configuredEnvironment, Path projectRoot) {
        validateLockedDecisionPolicy(configuredEnvironment);
        validateLockedOperationalPolicy(configuredEnvironment);
        validateMovementThresholdConfiguration(configuredEnvironment);
        String address = configuredEnvironment.getProperty("server.address", "127.0.0.1").trim();
        if (!isLoopbackAddress(address)) {
            throw unsafe("server.address debe ser una dirección loopback; la estación es local a una Mac.");
        }
        String managementAddress = configuredEnvironment.getProperty("management.server.address");
        if (managementAddress != null && !isLoopbackAddress(managementAddress.trim())) {
            throw unsafe(
                "management.server.address debe ser loopback; Actuator no puede enlazarse a la red."
            );
        }
        int port = configuredEnvironment.getProperty("server.port", Integer.class, 8080);
        if (port < 1024 || port > 65535) {
            throw unsafe("server.port debe estar entre 1024 y 65535 en la estación local.");
        }
        if (!configuredEnvironment.getProperty("handwash.session.access-required", Boolean.class, true)) {
            throw unsafe("handwash.session.access-required debe permanecer activado.");
        }
        Integer maxActiveSessions = configuredEnvironment.getProperty(
            "handwash.session.max-active-sessions", Integer.class);
        if (maxActiveSessions == null || maxActiveSessions != 1) {
            throw unsafe(
                "La estación de un solo lavamanos debe limitarse a una sesión de lavado activa."
            );
        }
        if (!configuredEnvironment.getProperty("handwash.producer.require-v2", Boolean.class, false)) {
            throw unsafe("handwash.producer.require-v2 debe estar activado en la estación.");
        }
        if (configuredEnvironment.getProperty("handwash.inference.api-enabled", Boolean.class, false)) {
            throw unsafe("handwash.inference.api-enabled debe permanecer desactivado en la estación.");
        }
        boolean omsModelReady = configuredEnvironment.getProperty(
            "handwash.oms.model-ready", Boolean.class, false);
        boolean omsInputEnabled = configuredEnvironment.getProperty(
            "handwash.oms.input-enabled", Boolean.class, false);
        boolean hospitalStation = java.util.Arrays.stream(configuredEnvironment.getActiveProfiles())
            .anyMatch("station"::equals);
        boolean demoStation = java.util.Arrays.stream(configuredEnvironment.getActiveProfiles())
            .anyMatch("station-demo"::equals);
        if (hospitalStation && demoStation) {
            throw unsafe("no se pueden activar simultáneamente los perfiles station y station-demo.");
        }
        if (omsModelReady != omsInputEnabled) {
            throw unsafe("handwash.oms.model-ready y handwash.oms.input-enabled deben habilitarse juntos.");
        }
        if ((omsModelReady || omsInputEnabled) && (!hospitalStation || demoStation)) {
            throw unsafe("el modelo y la ingestión OMS solo se permiten en station; "
                + "station-demo y los perfiles no hospitalarios deben mantenerlos desactivados.");
        }

        List<String> actuatorEndpoints = Binder.get(configuredEnvironment)
            .bind("management.endpoints.web.exposure.include", Bindable.listOf(String.class))
            .orElse(List.of("health"));
        if (actuatorEndpoints.size() != 1
            || !"health".equalsIgnoreCase(actuatorEndpoints.get(0).trim())) {
            throw unsafe("El perfil station solo puede exponer el endpoint Actuator health.");
        }

        String origins = configuredEnvironment.getProperty(
            "handwash.cors.allowed-origins", "http://127.0.0.1:8080,http://localhost:8080");
        boolean hasOrigin = false;
        Set<String> parsedOrigins = new HashSet<>();
        for (String candidate : origins.split(",")) {
            String origin = candidate.trim();
            if (origin.isEmpty()) continue;
            hasOrigin = true;
            if (!isLoopbackOrigin(origin)) {
                throw unsafe("CORS solo puede permitir orígenes loopback en una estación local.");
            }
            URI parsedOrigin = URI.create(origin);
            parsedOrigins.add(parsedOrigin.getScheme().toLowerCase(Locale.ROOT)
                + "://" + parsedOrigin.getRawAuthority().toLowerCase(Locale.ROOT));
        }
        if (!hasOrigin) {
            throw unsafe("La lista de orígenes CORS locales no puede estar vacía.");
        }
        Set<String> requiredOrigins = Set.of(
            "http://127.0.0.1:" + port,
            "http://localhost:" + port
        );
        if (!parsedOrigins.equals(requiredOrigins)) {
            throw unsafe(
                "CORS debe permitir únicamente localhost y 127.0.0.1 en el puerto local del dashboard."
            );
        }

        validateLocalDatasourceConfiguration(configuredEnvironment, projectRoot);
    }

    /** Called only after the independent signed-release verifier authorizes the hospital artifacts. */
    void validateAuthorizedHospitalConfiguration(Environment configuredEnvironment) {
        boolean hospitalStation = java.util.Arrays.stream(configuredEnvironment.getActiveProfiles())
            .anyMatch("station"::equals);
        boolean demoStation = java.util.Arrays.stream(configuredEnvironment.getActiveProfiles())
            .anyMatch("station-demo"::equals);
        boolean modelReady = configuredEnvironment.getProperty(
            "handwash.oms.model-ready", Boolean.class, false);
        boolean inputEnabled = configuredEnvironment.getProperty(
            "handwash.oms.input-enabled", Boolean.class, false);
        if (!hospitalStation || demoStation || !modelReady || !inputEnabled) {
            throw unsafe("un release hospitalario autorizado requiere handwash.oms.model-ready=true "
                + "y handwash.oms.input-enabled=true; el supervisor solo los habilita tras validar "
                + "la firma, los hashes, la taxonomía y la evidencia clínica.");
        }
    }

    private static void validateLockedDecisionPolicy(Environment configuredEnvironment) {
        LOCKED_DECISION_POLICY.forEach((property, expected) -> {
            if (!expected.equals(configuredEnvironment.getProperty(property))) {
                throw unsafe(property + " debe ser " + expected
                    + " en station/station-demo; modificarlo requiere revisar evidencia y firmar un release nuevo.");
            }
        });
    }

    private static void validateMovementThresholdConfiguration(Environment environment) {
        double startThreshold = environment.getProperty(
            "handwash.intention.start-minimum-normalized-movement", Double.class,
            CadenaIntencionLavado.DEFAULT_MOVEMENT_THRESHOLD);
        double stepThreshold = environment.getProperty(
            "handwash.intention.step-minimum-normalized-movement", Double.class,
            CadenaIntencionLavado.DEFAULT_MOVEMENT_THRESHOLD);
        if (!validMovementThreshold(startThreshold) || !validMovementThreshold(stepThreshold)) {
            throw unsafe("los umbrales normalizados de movimiento deben ser finitos, mayores que 0 y <= 1.");
        }
        boolean demoStation = java.util.Arrays.stream(environment.getActiveProfiles())
            .anyMatch("station-demo"::equals);
        if (demoStation && (Double.compare(startThreshold,
                CadenaIntencionLavado.DEFAULT_MOVEMENT_THRESHOLD) != 0
            || Double.compare(stepThreshold,
                CadenaIntencionLavado.DEFAULT_MOVEMENT_THRESHOLD) != 0)) {
            throw unsafe("station-demo debe conservar los umbrales no calibrados predeterminados.");
        }
    }

    private static boolean validMovementThreshold(double value) {
        return Double.isFinite(value) && value > 0.0 && value <= 1.0;
    }

    private static void validateLockedOperationalPolicy(Environment configuredEnvironment) {
        LOCKED_OPERATIONAL_POLICY.forEach((property, expected) -> {
            if (!expected.equals(configuredEnvironment.getProperty(property))) {
                throw unsafe(property + " debe ser " + expected
                    + " en station/station-demo; modificarlo requiere revisar capacidad y firmar un release nuevo.");
            }
        });
    }

    /**
     * Keeps the embedded database on the private station path and rejects H2
     * URL options or Spring overrides that could replace it or execute SQL at
     * connection/startup time.
     */
    private static void validateLocalDatasourceConfiguration(Environment environment, Path projectRoot) {
        String jdbcUrl = environment.getProperty("spring.datasource.url", "").trim();
        String prefix = "jdbc:h2:file:";
        if (!jdbcUrl.regionMatches(true, 0, prefix, 0, prefix.length())) {
            throw unsafe("La estación requiere H2 en archivo local y no permite una base remota.");
        }

        String[] urlParts = jdbcUrl.substring(prefix.length()).split(";", -1);
        try {
            Path databasePath = Path.of(urlParts[0]).normalize();
            Path normalizedProjectRoot = projectRoot.toAbsolutePath().normalize().toRealPath();
            Path expectedDatabasePath = normalizedProjectRoot.resolve(Path.of(".runtime", "handwash"));
            Path resolvedDatabasePath = databasePath.isAbsolute()
                ? databasePath.toAbsolutePath().normalize()
                : normalizedProjectRoot.resolve(databasePath).normalize();
            if (databasePath.isAbsolute()
                || !databasePath.equals(Path.of(".runtime", "handwash"))
                || !resolvedDatabasePath.equals(expectedDatabasePath)) {
                throw unsafe("La base local debe permanecer en .runtime/handwash.");
            }

            Path runtimeDirectory = normalizedProjectRoot.resolve(".runtime");
            if (Files.isSymbolicLink(runtimeDirectory)
                || (Files.exists(runtimeDirectory, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(runtimeDirectory, LinkOption.NOFOLLOW_LINKS))) {
                throw unsafe(".runtime debe ser un directorio local real, no un enlace ni otro tipo de archivo.");
            }
            if (Files.isSymbolicLink(expectedDatabasePath)) {
                throw unsafe("La ruta base de la base H2 no puede ser un enlace simbólico.");
            }
            for (String suffix : List.of(".mv.db", ".lock.db", ".trace.db", ".temp.db")) {
                if (Files.isSymbolicLink(Path.of(expectedDatabasePath + suffix))) {
                    throw unsafe("Los archivos de la base H2 no pueden ser enlaces simbólicos.");
                }
            }
        } catch (InvalidPathException | ArrayIndexOutOfBoundsException invalidPath) {
            throw unsafe("La ruta de la base H2 no es válida.");
        } catch (java.io.IOException inaccessiblePath) {
            throw unsafe("No se pudo comprobar la ruta local de la base H2.");
        }
        for (int index = 1; index < urlParts.length; index++) {
            if (!"DB_CLOSE_ON_EXIT=FALSE".equalsIgnoreCase(urlParts[index].trim())) {
                throw unsafe("La URL H2 contiene una opción no permitida; solo se admite DB_CLOSE_ON_EXIT=FALSE.");
            }
        }

        if (!"org.h2.Driver".equals(environment.getProperty(
                "spring.datasource.driver-class-name", "org.h2.Driver").trim())
            || !"sa".equals(environment.getProperty("spring.datasource.username", "sa").trim())
            || !environment.getProperty("spring.datasource.password", "").isEmpty()) {
            throw unsafe("La estación debe usar exclusivamente el datasource H2 local incluido en el release.");
        }

        for (String property : List.of(
            "spring.datasource.type",
            "spring.datasource.jndi-name",
            "spring.datasource.hikari.jdbc-url",
            "spring.datasource.hikari.data-source-class-name",
            "spring.datasource.hikari.driver-class-name",
            "spring.datasource.hikari.username",
            "spring.datasource.hikari.password",
            "spring.datasource.hikari.connection-init-sql",
            "spring.sql.init.platform")) {
            if (environment.getProperty(property) != null
                && !environment.getProperty(property).isBlank()) {
                throw unsafe(property + " no puede sobrescribir la configuración local revisada.");
            }
        }

        Map<String, String> datasourceProperties = Binder.get(environment)
            .bind("spring.datasource.hikari.data-source-properties",
                Bindable.mapOf(String.class, String.class))
            .orElse(Map.of());
        if (!datasourceProperties.isEmpty()) {
            throw unsafe("spring.datasource.hikari.data-source-properties no puede alterar la conexión H2.");
        }
        List<String> schemaLocations = Binder.get(environment)
            .bind("spring.sql.init.schema-locations", Bindable.listOf(String.class))
            .orElse(List.of());
        List<String> dataLocations = Binder.get(environment)
            .bind("spring.sql.init.data-locations", Bindable.listOf(String.class))
            .orElse(List.of());
        if (!schemaLocations.isEmpty() || !dataLocations.isEmpty()) {
            throw unsafe("La estación debe usar exclusivamente los scripts SQL incluidos en el release.");
        }
        if (!"always".equalsIgnoreCase(environment.getProperty("spring.sql.init.mode", "always").trim())
            || environment.getProperty("spring.sql.init.continue-on-error", Boolean.class, false)) {
            throw unsafe("La inicialización SQL debe usar el esquema incluido y fallar ante errores.");
        }
    }

    private static void secureRuntimeDirectory(Path projectRoot) {
        Path runtimeDirectory = projectRoot.toAbsolutePath().normalize().resolve(".runtime");
        try {
            ensurePrivateRuntimeDirectory(runtimeDirectory);
        } catch (java.io.IOException ioFailure) {
            throw unsafe("No se pudo proteger el directorio local .runtime.");
        }
    }

    private static void ensurePrivateRuntimeDirectory(Path runtimeDirectory) throws java.io.IOException {
        if (Files.isSymbolicLink(runtimeDirectory)) {
            throw unsafe(".runtime debe ser un directorio local real, no un enlace simbólico.");
        }
        if (Files.exists(runtimeDirectory, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(runtimeDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw unsafe(".runtime debe ser un directorio local real.");
            }
        } else {
            try {
                Files.createDirectory(runtimeDirectory,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } catch (UnsupportedOperationException unsupportedPermissions) {
                throw unsafe("El sistema de archivos debe permitir proteger .runtime con permisos POSIX.");
            }
        }
        try {
            // Failure-attempt summaries are local operational data; other local
            // accounts must not be able to list or read the station cache/logs.
            Files.setPosixFilePermissions(runtimeDirectory,
                PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException unsupportedPermissions) {
            throw unsafe("El sistema de archivos debe permitir proteger .runtime con permisos POSIX.");
        }
    }

    private static boolean isLoopbackAddress(String value) {
        if ("localhost".equalsIgnoreCase(value)) return true;
        // Only parse numeric IP literals; do not perform a DNS lookup for arbitrary config values.
        if (!value.matches("[0-9.]+|[0-9a-fA-F:]+")) return false;
        try {
            return InetAddress.getByName(value).isLoopbackAddress();
        } catch (Exception invalidAddress) {
            return false;
        }
    }

    private static boolean isLoopbackOrigin(String value) {
        try {
            URI origin = URI.create(value);
            String scheme = origin.getScheme();
            String host = origin.getHost();
            return "http".equalsIgnoreCase(scheme)
                && host != null
                && isLoopbackAddress(host)
                && origin.getUserInfo() == null
                && (origin.getPath() == null || origin.getPath().isEmpty())
                && origin.getQuery() == null
                && origin.getFragment() == null;
        } catch (IllegalArgumentException invalidOrigin) {
            return false;
        }
    }

    private static IllegalStateException unsafe(String message) {
        return new IllegalStateException("Configuración insegura del perfil station: " + message);
    }
}
