package com.handwash.config;

import com.handwash.intention.CadenaIntencionLavado;
import com.handwash.model.AccionOms;
import com.handwash.model.RegionJabon;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Verifies the signed clinical release gate before a station-profile backend starts. */
final class StationReleaseManifestVerifier {
    private static final long MAX_MANIFEST_BYTES = 2L * 1024 * 1024;
    private static final long MAX_SIGNATURE_BYTES = 16L * 1024;
    private static final long MAX_PUBLIC_KEY_BYTES = 64L * 1024;
    private static final long MAX_JAVA_ARTIFACT_BYTES = 1024L * 1024 * 1024;
    private static final long MAX_JAVA_SBOM_BYTES = 32L * 1024 * 1024;
    private static final long MAX_CAMERA_PYTHON_SBOM_BYTES = 32L * 1024 * 1024;
    private static final long MAX_CAMERA_RUNTIME_LOCK_BYTES = 128L * 1024;
    private static final long MAX_MODEL_BYTES = 2L * 1024 * 1024 * 1024;
    private static final long MAX_TRAINING_CONFIG_BYTES = 2L * 1024 * 1024;
    private static final long MAX_MOVEMENT_INTENT_EVIDENCE_BYTES = 2L * 1024 * 1024;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern LOCKED_PYTHON_REQUIREMENT = Pattern.compile(
        "([A-Za-z0-9][A-Za-z0-9_.-]*)==([A-Za-z0-9][A-Za-z0-9.+!-]*)");
    private static final Pattern PUBLIC_KEY_PEM = Pattern.compile(
        "\\A-----BEGIN PUBLIC KEY-----\\s+[A-Za-z0-9+/=\\r\\n]+\\s+-----END PUBLIC KEY-----\\s*\\z");
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build());

    private StationReleaseManifestVerifier() {}

    static void requireAuthorizedRelease(
        Path projectRoot, String publicKeyPathValue, Path runningJavaArtifact) {
        requireAuthorizedRelease(projectRoot, publicKeyPathValue, runningJavaArtifact,
            CadenaIntencionLavado.DEFAULT_MOVEMENT_THRESHOLD,
            CadenaIntencionLavado.DEFAULT_MOVEMENT_THRESHOLD);
    }

    static void requireAuthorizedRelease(Path projectRoot, String publicKeyPathValue,
                                         Path runningJavaArtifact,
                                         double configuredStartThreshold,
                                         double configuredStepThreshold) {
        if (publicKeyPathValue == null || publicKeyPathValue.isBlank()) {
            throw unsafe("falta HANDWASH_RELEASE_PUBLIC_KEY_PATH con la clave pública externa de confianza.");
        }

        try {
            Path root = projectRoot.toRealPath();
            // Keep these two paths lexical: resolving them first would follow a
            // final-component symlink before readBounded can reject it with
            // NOFOLLOW_LINKS, unlike the station supervisor's verifier.
            Path manifest = root.resolve("backend/models/model-manifest.json").normalize();
            Path signatureFile = root.resolve("backend/models/model-manifest.json.sig").normalize();
            Path configuredKey = Path.of(publicKeyPathValue.trim());
            if (!configuredKey.isAbsolute()) {
                throw unsafe("HANDWASH_RELEASE_PUBLIC_KEY_PATH debe ser una ruta absoluta.");
            }
            Path publicKey = configuredKey.toRealPath();
            if (!manifest.startsWith(root) || !signatureFile.startsWith(root) || publicKey.startsWith(root)) {
                throw unsafe("el manifiesto debe estar en el proyecto y la clave pública fuera del repositorio.");
            }

            byte[] manifestBytes = readBounded(manifest, MAX_MANIFEST_BYTES, "manifiesto");
            byte[] signatureBytes = readBounded(signatureFile, MAX_SIGNATURE_BYTES, "firma");
            byte[] publicKeyBytes = readBounded(publicKey, MAX_PUBLIC_KEY_BYTES, "clave pública");
            if (signatureBytes.length != 64) {
                throw unsafe("la firma Ed25519 del manifiesto debe tener exactamente 64 bytes.");
            }

            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(decodePublicKey(publicKeyBytes))));
            verifier.update(manifestBytes);
            if (!verifier.verify(signatureBytes)) {
                throw unsafe("la firma Ed25519 del manifiesto no coincide con la clave pública configurada.");
            }
            requireHospitalReadyMetadata(manifestBytes, root, runningJavaArtifact,
                configuredStartThreshold, configuredStepThreshold);
        } catch (IllegalStateException invalidRelease) {
            throw invalidRelease;
        } catch (IOException | GeneralSecurityException | IllegalArgumentException | JacksonException failure) {
            throw unsafe("no se pudo verificar el release firmado: " + failure.getMessage());
        }
    }

    private static byte[] readBounded(Path path, long limit, String description) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > limit) {
            throw unsafe("no existe un archivo válido de " + description + " o excede el límite permitido.");
        }
        // Do not rely on the earlier size check alone: a concurrently replaced or
        // growing file must not turn a small signed-metadata limit into an
        // unbounded allocation during station startup.
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(Math.toIntExact(limit + 1L));
        }
        if (bytes.length > limit) {
            throw unsafe("el archivo de " + description + " excede el límite permitido.");
        }
        return bytes;
    }

    private static byte[] decodePublicKey(byte[] encoded) {
        String pem = new String(encoded, StandardCharsets.US_ASCII);
        if (!PUBLIC_KEY_PEM.matcher(pem).matches()) {
            throw unsafe("la clave pública debe estar en formato PEM X.509 Ed25519.");
        }
        String base64 = pem.replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "");
        return Base64.getMimeDecoder().decode(base64);
    }

    private static void requireHospitalReadyMetadata(
        byte[] manifestBytes, Path projectRoot, Path runningJavaArtifact,
        double configuredStartThreshold, double configuredStepThreshold) throws IOException {
        JsonNode root = JSON.readTree(manifestBytes);
        if (root == null || !root.isObject()) {
            throw unsafe("el manifiesto firmado debe ser un objeto JSON.");
        }
        JsonNode readiness = root.path("deploymentReadiness");
        JsonNode training = root.path("active").path("trainingRun");
        JsonNode validation = root.path("active").path("validatedAgainst");
        JsonNode who = root.path("whoProcedure");
        JsonNode washingIntent = root.path("washingIntent");
        JsonNode artifacts = root.path("stationArtifacts");
        boolean modelArtifactValid = verifyActiveModel(root, projectRoot);
        List<String> requiredClasses = canonicalModelClasses();
        boolean trainingConfigValid = verifyTrainingDataConfig(training, projectRoot, requiredClasses);
        boolean javaSbomValid = verifyJavaSbom(artifacts, projectRoot);
        boolean cameraPythonSbomValid = verifyCameraPythonSbom(artifacts, projectRoot);
        boolean stationArtifactsValid = verifyStationArtifact(
            artifacts, "javaJarSha256", projectRoot, "backend/target/hand-wash-compliance-1.0.0.jar",
            runningJavaArtifact, MAX_JAVA_ARTIFACT_BYTES)
            && verifyStationArtifact(artifacts, "stationSupervisorSha256", projectRoot,
                "scripts/run_handwash_station.py", null, 10L * 1024 * 1024)
            && verifyStationArtifact(artifacts, "cameraProducerSha256", projectRoot,
                "scripts/run_yolo26_continuity_camera.py", null, 10L * 1024 * 1024)
            && verifyStationArtifact(artifacts, "cameraRequirementsLockSha256", projectRoot,
                "requirements-camera-macos-arm64.lock", null, 128L * 1024)
            && verifyStationArtifact(artifacts, "sbomGeneratorLockSha256", projectRoot,
                "requirements-sbom-generator-macos-arm64.lock", null, 128L * 1024);
        List<String> blockers = new ArrayList<>();
        requireText(readiness.path("status"), "READY_FOR_HOSPITAL_PILOT",
            "el estado no es READY_FOR_HOSPITAL_PILOT", blockers);
        requireTrue(readiness.path("hospitalUseAllowed"), "no autoriza uso hospitalario", blockers);
        requireTrue(readiness.path("stepTaxonomyVerified"), "la taxonomía no está verificada", blockers);
        requireTrue(readiness.path("independentVideoValidationPassed"),
            "falta validación independiente de video", blockers);
        requireTrue(readiness.path("continuityCameraPilotPassed"),
            "falta el piloto documentado de Continuity Camera", blockers);
        requireTrue(readiness.path("clinicalSafetyReviewPassed"),
            "falta la revisión clínica/de seguridad", blockers);
        if (!verifyMovementIntentEvidence(washingIntent, projectRoot,
            configuredStartThreshold, configuredStepThreshold)) {
            blockers.add("falta calibración independiente de los umbrales de movimiento/intención "
                + "y un reporte acotado con SHA-256 verificado");
        }
        if (!isExplicitTrue(training.path("trainingDataConfigIncluded")) || !trainingConfigValid) {
            blockers.add("el data.yaml exacto no está incluido, no coincide con su SHA-256 o "
                + "declara nombres/índices de clase distintos a la taxonomía firmada");
        }
        requireTrue(validation.path("independentValidationEligible"),
            "la validación no es independiente/elegible", blockers);
        requireText(who.path("status"), "VALIDATED_FOR_HOSPITAL_PILOT",
            "el procedimiento OMS no está validado para piloto", blockers);
        requireTrue(who.path("approvalAllowed"), "el procedimiento OMS no autoriza aprobación", blockers);
        if (!hasCanonicalOmsTaxonomy(root, who)) {
            blockers.add("las fases o las 36 clases del modelo no coinciden con la taxonomía OMS canónica");
        }
        if (!modelArtifactValid) {
            blockers.add("el checkpoint activo no existe, no coincide con su SHA-256 o contradice la tarea registrada");
        }
        if (!stationArtifactsValid) {
            blockers.add("las huellas del JAR, scripts, locks de runtime/generador o artefactos de estación no coinciden");
        }
        if (!javaSbomValid) {
            blockers.add("el SBOM Java falta, no es un CycloneDX 1.6 válido/no vacío o su SHA-256 no coincide");
        }
        if (!cameraPythonSbomValid) {
            blockers.add("el SBOM Python de cámara falta, no coincide con el lock exacto o su SHA-256 no coincide");
        }
        if (!hasNoDeclaredBlockers(readiness.path("blockers"))) {
            blockers.add("deploymentReadiness.blockers debe ser un arreglo explícito vacío");
        }
        if (!blockers.isEmpty()) {
            throw unsafe("faltan evidencias para piloto hospitalario: " + String.join("; ", blockers) + ".");
        }
    }

    private static boolean verifyMovementIntentEvidence(JsonNode washingIntent, Path projectRoot,
                                                        double configuredStartThreshold,
                                                        double configuredStepThreshold) {
        JsonNode startThreshold = washingIntent.path("startMinimumNormalizedMovement");
        JsonNode stepThreshold = washingIntent.path("stepMinimumNormalizedMovement");
        if (!validMovementThreshold(configuredStartThreshold)
            || !validMovementThreshold(configuredStepThreshold)
            || !startThreshold.isNumber() || !stepThreshold.isNumber()
            || Double.compare(startThreshold.doubleValue(), configuredStartThreshold) != 0
            || Double.compare(stepThreshold.doubleValue(), configuredStepThreshold) != 0) {
            return false;
        }
        if (!isExplicitTrue(washingIntent.path("startUsesMovementMagnitudeThreshold"))
            || !isExplicitTrue(washingIntent.path(
                "startUsesClinicallyCalibratedMovementMagnitudeThreshold"))
            || !isExplicitTrue(washingIntent.path("stepCreditUsesMovementMagnitudeThreshold"))
            || !isExplicitTrue(washingIntent.path(
                "stepCreditUsesClinicallyCalibratedMovementMagnitudeThreshold"))) {
            return false;
        }
        JsonNode evidence = washingIntent.path("validationEvidence");
        JsonNode reportPathNode = evidence.path("reportPath");
        JsonNode reportDigestNode = evidence.path("reportSha256");
        if (!"VALIDATED_FOR_HOSPITAL_PILOT".equals(evidence.path("status").asText())
            || !isExplicitTrue(evidence.path("startIntentIndependentlyValidated"))
            || !reportPathNode.isTextual() || reportPathNode.asText().isBlank()
            || !reportDigestNode.isTextual() || !SHA256.matcher(reportDigestNode.asText()).matches()) {
            return false;
        }

        try {
            Path relative = Path.of(reportPathNode.asText());
            if (relative.isAbsolute()) return false;
            for (Path part : relative) {
                if ("..".equals(part.toString())) return false;
            }
            Path artifact = projectRoot;
            for (Path part : relative) {
                artifact = artifact.resolve(part);
                if (Files.isSymbolicLink(artifact)) return false;
            }
            if (!artifact.normalize().startsWith(projectRoot)
                || !Files.isRegularFile(artifact, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            byte[] report = readBounded(
                artifact, MAX_MOVEMENT_INTENT_EVIDENCE_BYTES, "reporte de calibración de intención");
            return report.length > 0
                && reportDigestNode.asText().equalsIgnoreCase(sha256(report));
        } catch (IOException | GeneralSecurityException | IllegalArgumentException invalidEvidence) {
            return false;
        }
    }

    private static boolean validMovementThreshold(double value) {
        return Double.isFinite(value) && value > 0.0 && value <= 1.0;
    }

    /**
     * A signed boolean cannot compensate for a missing or mismatched model artifact.
     * Bind the actual checkpoint bytes and its exact taxonomy to the signed release.
     */
    private static boolean verifyActiveModel(JsonNode manifest, Path projectRoot) {
        JsonNode active = manifest.path("active");
        JsonNode training = active.path("trainingRun");
        JsonNode digest = active.path("sha256");
        JsonNode checkpointDigest = training.path("checkpointSha256");
        JsonNode modelPath = active.path("path");
        if (!"detect".equals(active.path("task").asText())
            || !"detect".equals(training.path("task").asText())
            || !isExplicitTrue(training.path("identicalToActiveCheckpoint"))
            || !digest.isTextual() || !SHA256.matcher(digest.asText()).matches()
            || !checkpointDigest.isTextual()
            || !digest.asText().equalsIgnoreCase(checkpointDigest.asText())
            || !modelPath.isTextual() || modelPath.asText().isBlank()) {
            return false;
        }
        try {
            Path relative = Path.of(modelPath.asText());
            if (relative.isAbsolute()) return false;
            Path artifact = projectRoot.resolve(relative).normalize().toRealPath();
            return artifact.startsWith(projectRoot)
                && Files.isRegularFile(artifact)
                && Files.size(artifact) > 0
                && Files.size(artifact) <= MAX_MODEL_BYTES
                && digest.asText().equalsIgnoreCase(sha256(artifact, MAX_MODEL_BYTES));
        } catch (IOException | GeneralSecurityException | IllegalArgumentException invalidArtifact) {
            return false;
        }
    }

    /** The signed release must bind the exact training dataset config, not just assert it exists. */
    private static boolean verifyTrainingDataConfig(
        JsonNode training, Path projectRoot, List<String> requiredClasses) {
        JsonNode pathNode = training.path("trainingDataConfigPath");
        JsonNode digestNode = training.path("trainingDataConfigSha256");
        if (!pathNode.isTextual() || pathNode.asText().isBlank()
            || !digestNode.isTextual() || !SHA256.matcher(digestNode.asText()).matches()) {
            return false;
        }
        try {
            Path relative = Path.of(pathNode.asText());
            if (relative.isAbsolute()) return false;
            Path artifact = projectRoot.resolve(relative).normalize().toRealPath();
            if (!artifact.startsWith(projectRoot)) return false;
            byte[] bytes = readBounded(artifact, MAX_TRAINING_CONFIG_BYTES, "data.yaml de entrenamiento");
            return bytes.length > 0 && bytes.length <= MAX_TRAINING_CONFIG_BYTES
                && digestNode.asText().equalsIgnoreCase(sha256(bytes))
                && trainingConfigHasCanonicalClasses(bytes, requiredClasses);
        } catch (IOException | GeneralSecurityException | IllegalArgumentException invalidConfig) {
            return false;
        }
    }

    /** Verify the exact class order used by the training YAML, not only its signed hash. */
    private static boolean trainingConfigHasCanonicalClasses(
        byte[] bytes, List<String> requiredClasses) {
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(0);
            options.setCodePointLimit((int) MAX_TRAINING_CONFIG_BYTES);
            options.setNestingDepthLimit(32);
            String yamlText = StandardCharsets.UTF_8.newDecoder()
                .decode(ByteBuffer.wrap(bytes)).toString();
            Object document = new Yaml(new SafeConstructor(options)).load(yamlText);
            if (!(document instanceof Map<?, ?> config)) return false;
            Object names = config.get("names");
            if (names instanceof List<?> list) {
                return list.equals(requiredClasses);
            }
            if (!(names instanceof Map<?, ?> mapping) || mapping.size() != requiredClasses.size()) {
                return false;
            }
            String[] ordered = new String[requiredClasses.size()];
            for (Map.Entry<?, ?> entry : mapping.entrySet()) {
                Integer index = classIndex(entry.getKey());
                if (index == null || index < 0 || index >= ordered.length
                    || ordered[index] != null || !(entry.getValue() instanceof String name)) {
                    return false;
                }
                ordered[index] = name;
            }
            return Arrays.asList(ordered).equals(requiredClasses);
        } catch (IOException | RuntimeException malformedYaml) {
            return false;
        }
    }

    private static Integer classIndex(Object value) {
        if (value instanceof Integer index) return index;
        if (!(value instanceof String text) || !text.matches("(?:0|[1-9][0-9]*)")) return null;
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException outOfRange) {
            return null;
        }
    }

    private static List<String> canonicalModelClasses() {
        List<String> requiredClasses = new ArrayList<>();
        AccionOms.SECUENCIA.stream().map(AccionOms::getClaseModelo).forEach(requiredClasses::add);
        requiredClasses.add(AccionOms.CONTACTO_RIESGO.getClaseModelo());
        for (RegionJabon region : RegionJabon.values()) {
            requiredClasses.add(region.claseEspumaVisible());
            requiredClasses.add(region.claseSinEspumaVisible());
        }
        return List.copyOf(requiredClasses);
    }

    /**
     * The Java evaluator's canonical phase order and the training rubric must agree.
     * The active WHO detector must expose all phase, risk and bilateral soap classes.
     */
    private static boolean hasCanonicalOmsTaxonomy(JsonNode manifest, JsonNode who) {
        if (!manifest.path("schemaVersion").isIntegralNumber()
            || !manifest.path("schemaVersion").canConvertToInt()
            || manifest.path("schemaVersion").intValue() != 1) return false;

        List<String> expectedPhases = AccionOms.SECUENCIA.stream()
            .map(Enum::name).toList();
        if (!matchesOrderedTextArray(who.path("requiredPhases"), expectedPhases)) return false;

        JsonNode declaredClasses = manifest.path("active").path("modelClassNames");
        return matchesOrderedTextArray(declaredClasses, canonicalModelClasses());
    }

    private static boolean matchesOrderedTextArray(JsonNode actual, List<String> expected) {
        if (!actual.isArray() || actual.size() != expected.size()) return false;
        for (int index = 0; index < expected.size(); index++) {
            JsonNode item = actual.get(index);
            if (!item.isTextual() || !expected.get(index).equals(item.asText())) return false;
        }
        return true;
    }

    private static void requireText(JsonNode actual, String expected, String reason, List<String> blockers) {
        if (!actual.isTextual() || !expected.equals(actual.asText())) blockers.add(reason);
    }

    private static void requireTrue(JsonNode actual, String reason, List<String> blockers) {
        if (!isExplicitTrue(actual)) blockers.add(reason);
    }

    private static boolean verifyStationArtifact(
        JsonNode artifacts, String hashField, Path projectRoot, String relativePath,
        Path runningJavaArtifact, long maximumBytes) {
        JsonNode expectedNode = artifacts.path(hashField);
        if (!expectedNode.isTextual() || !SHA256.matcher(expectedNode.asText()).matches()) return false;
        try {
            Path expectedPath = projectRoot.resolve(relativePath).normalize().toRealPath();
            if (!expectedPath.startsWith(projectRoot) || !Files.isRegularFile(expectedPath)
                || Files.size(expectedPath) <= 0 || Files.size(expectedPath) > maximumBytes) return false;
            if (runningJavaArtifact != null) {
                Path actualPath = runningJavaArtifact.toRealPath();
                if (!actualPath.startsWith(projectRoot) || !actualPath.equals(expectedPath)) return false;
            } else if ("javaJarSha256".equals(hashField)) {
                // A station may not start from classes/ or an unrecognized launcher artifact.
                return false;
            }
            return expectedNode.asText().equalsIgnoreCase(sha256(expectedPath, maximumBytes));
        } catch (IOException | GeneralSecurityException invalidArtifact) {
            return false;
        }
    }

    /** Bind the Java dependency inventory to the signed release, not just the executable JAR. */
    private static boolean verifyJavaSbom(JsonNode artifacts, Path projectRoot) {
        JsonNode expectedNode = artifacts.path("javaSbomSha256");
        if (!expectedNode.isTextual() || !SHA256.matcher(expectedNode.asText()).matches()) return false;
        try {
            Path sbomPath = projectRoot.resolve("backend/target/handwash-java-sbom.json")
                .normalize().toRealPath();
            if (!sbomPath.startsWith(projectRoot)) return false;
            byte[] bytes = readBounded(sbomPath, MAX_JAVA_SBOM_BYTES, "SBOM Java");
            if (bytes.length == 0 || !expectedNode.asText().equalsIgnoreCase(sha256(bytes))) return false;
            JsonNode bom = JSON.readTree(bytes);
            JsonNode application = bom == null ? null : bom.path("metadata").path("component");
            JsonNode components = bom == null ? null : bom.path("components");
            if (bom == null
                || !"CycloneDX".equals(bom.path("bomFormat").asText())
                || !"1.6".equals(bom.path("specVersion").asText())
                || !bom.path("version").isIntegralNumber()
                || !bom.path("version").canConvertToLong()
                || bom.path("version").longValue() <= 0
                || !"application".equals(application.path("type").asText())
                || !application.path("name").isTextual()
                || application.path("name").asText().isBlank()
                || !components.isArray() || components.isEmpty()) {
                return false;
            }
            for (JsonNode component : components) {
                if (!component.isObject()) return false;
            }
            return true;
        } catch (IOException | GeneralSecurityException | IllegalArgumentException | JacksonException invalidSbom) {
            return false;
        }
    }

    /** Bind the camera Python inventory to the exact locked package/version closure. */
    private static boolean verifyCameraPythonSbom(JsonNode artifacts, Path projectRoot) {
        JsonNode expectedNode = artifacts.path("cameraPythonSbomSha256");
        if (!expectedNode.isTextual() || !SHA256.matcher(expectedNode.asText()).matches()) return false;
        try {
            Path sbomPath = projectRoot.resolve("backend/target/handwash-camera-python-sbom.json")
                .normalize().toRealPath();
            if (!sbomPath.startsWith(projectRoot)) return false;
            byte[] bytes = readBounded(sbomPath, MAX_CAMERA_PYTHON_SBOM_BYTES, "SBOM Python de cámara");
            if (bytes.length == 0 || !expectedNode.asText().equalsIgnoreCase(sha256(bytes))) return false;
            JsonNode bom = JSON.readTree(bytes);
            JsonNode components = bom == null ? null : bom.path("components");
            if (bom == null || !"CycloneDX".equals(bom.path("bomFormat").asText())
                || !"1.6".equals(bom.path("specVersion").asText())
                || !bom.path("version").isIntegralNumber() || !bom.path("version").canConvertToLong()
                || bom.path("version").longValue() < 1 || !components.isArray() || components.isEmpty()) {
                return false;
            }

            Map<String, String> actual = new HashMap<>();
            for (JsonNode component : components) {
                JsonNode name = component.path("name");
                JsonNode version = component.path("version");
                if (!component.isObject() || !name.isTextual() || name.asText().isBlank()
                    || !version.isTextual() || version.asText().isBlank()) return false;
                String normalizedName = normalizePythonPackageName(name.asText());
                if (actual.putIfAbsent(normalizedName, version.asText()) != null) return false;
            }

            Path lockPath = projectRoot.resolve("requirements-camera-macos-arm64.lock")
                .normalize().toRealPath();
            if (!lockPath.startsWith(projectRoot)) return false;
            byte[] lockBytes = readBounded(lockPath, MAX_CAMERA_RUNTIME_LOCK_BYTES, "lock Python de cámara");
            Map<String, String> expected = parseLockedPythonRequirements(lockBytes);
            return !expected.isEmpty() && actual.equals(expected);
        } catch (IOException | GeneralSecurityException | IllegalArgumentException | JacksonException invalidSbom) {
            return false;
        }
    }

    private static Map<String, String> parseLockedPythonRequirements(byte[] lockBytes) throws IOException {
        String lock = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(lockBytes)).toString();
        Map<String, String> requirements = new HashMap<>();
        for (String rawLine : lock.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            var match = LOCKED_PYTHON_REQUIREMENT.matcher(line);
            if (!match.matches()) return Map.of();
            String name = normalizePythonPackageName(match.group(1));
            if (requirements.putIfAbsent(name, match.group(2)) != null) return Map.of();
        }
        return requirements;
    }

    private static String normalizePythonPackageName(String name) {
        return name.replaceAll("[-_.]+", "-").toLowerCase(Locale.ROOT);
    }

    private static String sha256(Path path, long maximumBytes) throws IOException, GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long totalBytes = 0L;
        try (InputStream input = Files.newInputStream(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                totalBytes += read;
                if (totalBytes > maximumBytes) {
                    throw new IOException("El archivo excede el límite al calcular SHA-256.");
                }
                digest.update(buffer, 0, read);
            }
        }
        if (totalBytes == 0L) throw new IOException("El archivo vacío no es un artefacto de release válido.");
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static boolean isExplicitTrue(JsonNode value) {
        return value.isBoolean() && value.booleanValue();
    }

    private static boolean hasNoDeclaredBlockers(JsonNode blockers) {
        // A release is clear only when the field is an explicit empty array.
        // Whitespace, null, or empty-string entries are malformed blockers,
        // not evidence that the reviewer cleared the release.
        return blockers.isArray() && blockers.isEmpty();
    }

    private static IllegalStateException unsafe(String message) {
        return new IllegalStateException("Release de estación no autorizado: " + message);
    }
}
