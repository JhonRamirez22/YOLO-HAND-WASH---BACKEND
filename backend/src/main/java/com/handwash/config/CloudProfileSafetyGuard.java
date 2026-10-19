package com.handwash.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Refuses unsafe or incomplete cloud configuration; never logs credentials or URLs. */
@Component
@Profile("cloud")
public final class CloudProfileSafetyGuard implements BeanFactoryPostProcessor, EnvironmentAware {
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
        throws BeansException {
        validateConfiguration(environment);
    }

    void validateConfiguration(Environment env) {
        // On-premises (PC with Docker, no AWS) is the cloud profile plus "onprem"; nothing else may be mixed in.
        boolean onprem = env != null && Arrays.equals(env.getActiveProfiles(), new String[] {"cloud", "onprem"});
        if (env == null || !(onprem || Arrays.equals(env.getActiveProfiles(), new String[] {"cloud"}))) {
            throw unsafe("el perfil cloud debe ser el único perfil Spring activo (o cloud + onprem).");
        }
        require("server.address", env.getProperty("server.address"), "0.0.0.0");
        require("handwash.deployment.mode", env.getProperty("handwash.deployment.mode"),
            "NON_CLINICAL_DEMO");
        require("spring.sql.init.mode", env.getProperty("spring.sql.init.mode"), "never");
        require("spring.flyway.clean-disabled", env.getProperty("spring.flyway.clean-disabled"),
            "true");
        require("handwash.oms.model-ready", env.getProperty("handwash.oms.model-ready"), "false");
        require("handwash.oms.input-enabled", env.getProperty("handwash.oms.input-enabled"), "false");
        validateEvidenceCapture(env);
        if (onprem) require("handwash.evidence.capture-enabled", env.getProperty("handwash.evidence.capture-enabled", "false"), "false");
        validateAuditChain(env, onprem);
        require("handwash.video.remote-enabled", env.getProperty("handwash.video.remote-enabled"),
            "false");
        require("handwash.inference.api-enabled", env.getProperty("handwash.inference.api-enabled"),
            "false");
        require("handwash.security.producer-jwt-enabled",
            env.getProperty("handwash.security.producer-jwt-enabled"), "true");
        require("handwash.producer.require-v2", env.getProperty("handwash.producer.require-v2"), "true");

        String databaseUrl = required(env, "spring.datasource.url");
        String migrationUrl = required(env, "spring.flyway.url");
        DatabaseEndpoint database = onprem ? privateNetworkPostgresUrl("spring.datasource.url", databaseUrl)
            : securePostgresUrl("spring.datasource.url", databaseUrl);
        DatabaseEndpoint migrationDatabase = onprem ? privateNetworkPostgresUrl("spring.flyway.url", migrationUrl)
            : securePostgresUrl("spring.flyway.url", migrationUrl);
        if (!database.host().equalsIgnoreCase(migrationDatabase.host())
            || database.port() != migrationDatabase.port()
            || !database.path().equals(migrationDatabase.path())) {
            throw unsafe("la cuenta de migración debe apuntar a la misma base PostgreSQL.");
        }
        String appUser = required(env, "spring.datasource.username");
        String appPassword = required(env, "spring.datasource.password");
        String migrationUser = required(env, "spring.flyway.user");
        String migrationPassword = required(env, "spring.flyway.password");
        if (appUser.equals(migrationUser) || appPassword.equals(migrationPassword)) {
            throw unsafe("la aplicación y Flyway deben usar credenciales distintas.");
        }

        if (!onprem) {  // there is no Cognito on-premises: ADMIN uses local accounts and everyone else API keys
            secureIssuer(required(env,
                "spring.security.oauth2.resourceserver.jwt.issuer-uri"));
            String userClientId = required(env, "handwash.security.cognito-client-id");
            String producerClientId = required(env, "handwash.security.cognito-producer-client-id");
            if (userClientId.equals(producerClientId)) {
                throw unsafe("Cognito debe separar el cliente SPA del cliente productor.");
            }
        }
        secureOrigins(required(env, "handwash.cors.allowed-origins"));
        String modelVersion = required(env, "handwash.audit.model-version");
        String datasetVersion = required(env, "handwash.audit.dataset-version");
        if (modelVersion.equalsIgnoreCase("UNVERIFIED") || modelVersion.length() > 128
            || datasetVersion.equalsIgnoreCase("UNVERIFIED") || datasetVersion.length() > 128) {
            throw unsafe("las versiones de modelo y dataset deben ser explícitas y acotadas.");
        }
        String modelHash = required(env, "handwash.audit.model-sha256");
        if (!modelHash.matches("(?i)[a-f0-9]{64}")) {
            throw unsafe("handwash.audit.model-sha256 debe ser SHA-256 hexadecimal.");
        }
        int poolMax = env.getProperty("spring.datasource.hikari.maximum-pool-size", Integer.class, 8);
        if (poolMax < 1 || poolMax > 32) {
            throw unsafe("el pool PostgreSQL debe tener entre 1 y 32 conexiones.");
        }
    }

    private static DatabaseEndpoint securePostgresUrl(String property, String value) {
        if (!value.startsWith("jdbc:postgresql://")) {
            throw unsafe(property + " debe usar PostgreSQL.");
        }
        try {
            URI uri = URI.create(value.substring("jdbc:".length()));
            if (uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
                throw unsafe(property + " no debe incluir credenciales ni fragmentos en la URL.");
            }
            Map<String, String> query = parseQuery(uri.getRawQuery());
            if (!query.keySet().equals(java.util.Set.of("sslmode", "sslrootcert"))) {
                throw unsafe(property + " solo admite sslmode y sslrootcert en la URL.");
            }
            if (!"verify-full".equals(query.get("sslmode"))) {
                throw unsafe(property + " debe requerir sslmode=verify-full.");
            }
            String caFile = query.get("sslrootcert");
            if (caFile == null || caFile.isBlank()) {
                throw unsafe(property + " debe definir sslrootcert.");
            }
            Path ca = Path.of(caFile);
            if (!ca.isAbsolute() || !Files.isRegularFile(ca) || !Files.isReadable(ca)) {
                throw unsafe(property + " requiere un certificado CA absoluto, regular y legible.");
            }
            if (uri.getHost() == null || uri.getPath() == null || uri.getPath().length() < 2) {
                throw unsafe(property + " debe identificar host y base PostgreSQL.");
            }
            int port = uri.getPort() < 0 ? 5432 : uri.getPort();
            if (port < 1 || port > 65_535) {
                throw unsafe(property + " debe incluir un puerto PostgreSQL válido.");
            }
            return new DatabaseEndpoint(uri.getHost(), port, uri.getPath());
        } catch (IllegalArgumentException ex) {
            throw unsafe(property + " no es una URL PostgreSQL válida.");
        }
    }

    /** PostgreSQL on the same machine/compose network: no TLS is needed, but no remote host is ever accepted without it. */
    private static DatabaseEndpoint privateNetworkPostgresUrl(String property, String value) {
        if (!value.startsWith("jdbc:postgresql://")) throw unsafe(property + " debe usar PostgreSQL.");
        try {
            URI uri = URI.create(value.substring("jdbc:".length()));
            if (uri.getRawUserInfo() != null || uri.getRawFragment() != null || uri.getRawQuery() != null) {
                throw unsafe(property + " no admite credenciales, fragmentos ni parámetros en la URL local.");
            }
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!LOCAL_DATABASE_HOSTS.contains(host)) {
                throw unsafe(property + " sin TLS solo se acepta hacia la red local (db, localhost, 127.0.0.1).");
            }
            if (uri.getPath() == null || uri.getPath().length() < 2) {
                throw unsafe(property + " debe identificar la base PostgreSQL.");
            }
            int port = uri.getPort() < 0 ? 5432 : uri.getPort();
            return new DatabaseEndpoint(host, port, uri.getPath());
        } catch (IllegalArgumentException ex) {
            throw unsafe(property + " no es una URL PostgreSQL válida.");
        }
    }

    private static final java.util.Set<String> LOCAL_DATABASE_HOSTS =
        java.util.Set.of("db", "postgres", "localhost", "127.0.0.1", "host.docker.internal");

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new HashMap<>();
        if (rawQuery == null) return result;
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw unsafe("la query de la URL PostgreSQL debe tener parámetros completos.");
            }
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            String value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            if (result.putIfAbsent(key, value) != null) {
                throw unsafe("la URL PostgreSQL no admite parámetros duplicados.");
            }
        }
        return result;
    }

    private static void secureIssuer(String rawIssuer) {
        URI issuer = parseHttpsUri("COGNITO_ISSUER_URI", rawIssuer);
        String host = issuer.getHost().toLowerCase(Locale.ROOT);
        if (!host.startsWith("cognito-idp.")
            || !(host.endsWith(".amazonaws.com") || host.endsWith(".amazonaws.com.cn"))
            || issuer.getPath() == null || !issuer.getPath().matches("/[a-z0-9-]+_[A-Za-z0-9]+/?")) {
            throw unsafe("el emisor JWT debe ser un issuer de Cognito User Pool.");
        }
    }

    private static void secureOrigins(String rawOrigins) {
        String[] origins = rawOrigins.split(",");
        if (origins.length == 0) throw unsafe("HANDWASH_CORS_ALLOWED_ORIGINS es obligatorio.");
        for (String origin : origins) {
            if (origin.isBlank() || origin.contains("*") || !origin.equals(origin.trim())) {
                throw unsafe("CORS solo admite orígenes HTTPS exactos.");
            }
            URI uri = parseHttpsUri("HANDWASH_CORS_ALLOWED_ORIGINS", origin);
            if (uri.getHost() == null || uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                || uri.getRawQuery() != null || uri.getFragment() != null) {
                throw unsafe("CORS solo admite orígenes HTTPS sin ruta ni parámetros.");
            }
        }
    }

    private static URI parseHttpsUri(String name, String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null) {
                throw unsafe(name + " debe usar HTTPS sin credenciales embebidas.");
            }
            if (uri.getHost() == null) throw unsafe(name + " debe incluir un host válido.");
            return uri;
        } catch (IllegalArgumentException ex) {
            throw unsafe(name + " no es válido.");
        }
    }

    /** The audit chain is only tamper-evident against database writers when keyed with a secret they do not hold. */
    private static void validateAuditChain(Environment env, boolean onprem) {
        String secret = required(env, "handwash.audit.chain-secret");
        if (secret.length() < 32) throw unsafe("handwash.audit.chain-secret debe tener al menos 32 caracteres.");
        if (onprem) {
            Path seals = Path.of(required(env, "handwash.audit.seal-dir"));
            if (!seals.isAbsolute() || !Files.isDirectory(seals) || !Files.isWritable(seals)) {
                throw unsafe("handwash.audit.seal-dir debe ser un directorio absoluto y escribible.");
            }
            return;
        }
        String bucket = env.getProperty("handwash.audit.seal-bucket", "");
        String key = env.getProperty("handwash.audit.seal-kms-key-id", "");
        if (bucket.isBlank() != key.isBlank()) {
            throw unsafe("handwash.audit.seal-bucket y handwash.audit.seal-kms-key-id van juntos.");
        }
    }

    /** Photos are off unless the deployment names an encrypted bucket and a bounded retention. */
    private static void validateEvidenceCapture(Environment env) {
        String capture = env.getProperty("handwash.evidence.capture-enabled", "false");
        if ("false".equals(capture)) return;
        if (!"true".equals(capture)) throw unsafe("handwash.evidence.capture-enabled debe ser true o false.");
        required(env, "handwash.evidence.bucket");
        required(env, "handwash.evidence.kms-key-id");
        String retention = env.getProperty("handwash.evidence.retention-days", "30");
        try {
            int days = Integer.parseInt(retention.trim());
            if (days < 1 || days > 90) throw new NumberFormatException(retention);
        } catch (NumberFormatException invalid) {
            throw unsafe("handwash.evidence.retention-days debe estar entre 1 y 90.");
        }
    }

    private static String required(Environment env, String key) {
        String value = env.getProperty(key);
        if (value == null || value.isBlank() || value.contains("${")
            || "UNVERIFIED".equalsIgnoreCase(value)) {
            throw unsafe(key + " es obligatorio y debe estar verificado.");
        }
        return value;
    }

    private static void require(String key, String value, String expected) {
        if (!expected.equals(value)) throw unsafe(key + " debe permanecer en " + expected + ".");
    }

    private static UnsafeCloudConfigurationException unsafe(String message) {
        return new UnsafeCloudConfigurationException(message);
    }

    private record DatabaseEndpoint(String host, int port, String path) {}

    static final class UnsafeCloudConfigurationException extends IllegalStateException {
        UnsafeCloudConfigurationException(String message) { super(message); }
    }
}
