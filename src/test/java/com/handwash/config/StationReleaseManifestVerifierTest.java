package com.handwash.config;

import com.handwash.model.OmsAction;
import com.handwash.model.SoapRegion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StationReleaseManifestVerifierTest {
    @TempDir Path temporaryDirectory;

    @Test
    void acceptsOnlySignedManifestWithCompleteReleaseEvidence() throws Exception {
        ReleaseFixture fixture = fixture(readyManifest());

        assertDoesNotThrow(() -> StationReleaseManifestVerifier.requireAuthorizedRelease(
            fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
    }

    @Test
    void rejectsSignedManifestWithoutMovementCalibrationEvidence() throws Exception {
        ReleaseFixture fixture = fixture(readyManifest().replace(
            "\"startUsesClinicallyCalibratedMovementMagnitudeThreshold\":true,", ""));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
        assertTrue(failure.getMessage().contains("umbrales de movimiento"));
    }

    @Test
    void rejectsMovementCalibrationReportTamperedAfterSigning() throws Exception {
        ReleaseFixture fixture = fixture(readyManifest());
        Path report = fixture.projectRoot().resolve("backend/models/test-movement-intent-evidence.txt");
        Files.writeString(report, "tampered after signing");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
        assertTrue(failure.getMessage().contains("umbrales de movimiento"));
    }

    @Test
    void rejectsSignedMovementThresholdsThatDoNotMatchTheRuntimeConfiguration() throws Exception {
        String mismatched = readyManifest().replace(
            "\"startMinimumNormalizedMovement\":1.0E-8",
            "\"startMinimumNormalizedMovement\":0.05");
        ReleaseFixture fixture = fixture(mismatched);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
        assertTrue(failure.getMessage().contains("umbrales de movimiento"));
    }

    @Test
    void acceptsSignedThresholdsOnlyWhenTheStationRuntimeUsesThoseExactValues() throws Exception {
        String calibrated = readyManifest().replace(
            "\"startMinimumNormalizedMovement\":1.0E-8",
            "\"startMinimumNormalizedMovement\":0.05");
        ReleaseFixture fixture = fixture(calibrated);

        assertDoesNotThrow(() -> StationReleaseManifestVerifier.requireAuthorizedRelease(
            fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar(), 0.05, 1.0e-8));
    }

    @Test
    void rejectsSymlinkedMovementCalibrationReport() throws Exception {
        String manifest = readyManifest().replace(
            "backend/models/test-movement-intent-evidence.txt",
            "backend/models/movement-intent-evidence-link.txt");
        ReleaseFixture fixture = fixture(manifest);
        Path report = fixture.projectRoot().resolve("backend/models/test-movement-intent-evidence.txt");
        Path alias = fixture.projectRoot().resolve("backend/models/movement-intent-evidence-link.txt");
        Files.createSymbolicLink(alias, report.getFileName());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
        assertTrue(failure.getMessage().contains("umbrales de movimiento"));
    }

    @Test
    void rejectsManifestModifiedAfterSigning() throws Exception {
        ReleaseFixture fixture = fixture(readyManifest());
        Files.writeString(fixture.manifest(), readyManifest().replace("READY_FOR_HOSPITAL_PILOT", "NOT_READY"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
        assertTrue(failure.getMessage().contains("firma Ed25519"));
    }

    @Test
    void rejectsManifestSymlinkLikeTheStationSupervisor() throws Exception {
        ReleaseFixture fixture = fixture(readyManifest());
        Path signedContents = fixture.manifest().resolveSibling("signed-manifest.json");
        Files.move(fixture.manifest(), signedContents);
        Files.createSymbolicLink(fixture.manifest(), signedContents.getFileName());

        assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
    }

    @Test
    void rejectsSignatureSymlinkLikeTheStationSupervisor() throws Exception {
        ReleaseFixture fixture = fixture(readyManifest());
        Path signature = fixture.manifest().resolveSibling("model-manifest.json.sig");
        Path signedSignature = signature.resolveSibling("signed-manifest.sig");
        Files.move(signature, signedSignature);
        Files.createSymbolicLink(signature, signedSignature.getFileName());

        assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
    }

    @Test
    void rejectsSignedManifestWithDuplicateJsonKeys() throws Exception {
        String ambiguous = readyManifest().replace(
            "\"schemaVersion\":1,", "\"schemaVersion\":1,\"schemaVersion\":1,");
        ReleaseFixture fixture = fixture(ambiguous);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
        assertTrue(failure.getMessage().contains("no se pudo verificar el release firmado"));
    }

    @Test
    void rejectsSchemaVersionThatOverflowsJavaIntegerConversion() throws Exception {
        ReleaseFixture fixture = fixture(readyManifest().replace(
            "\"schemaVersion\":1", "\"schemaVersion\":4294967297"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
        assertTrue(failure.getMessage().contains("fases o las 36 clases"));
    }

    @Test
    void rejectsSignedManifestWithoutIndependentClinicalReadiness() throws Exception {
        String unready = readyManifest().replace("\"independentVideoValidationPassed\":true",
            "\"independentVideoValidationPassed\":false");
        ReleaseFixture fixture = fixture(unready);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
        assertTrue(failure.getMessage().contains("validación independiente"));
    }

    @Test
    void rejectsSignedManifestWithMissingOrReorderedWhoPhase() throws Exception {
        String malformed = readyManifest().replace(
            "\"requiredPhases\":[\"MOJAR_MANOS\"",
            "\"requiredPhases\":[\"APLICAR_JABON\"");
        ReleaseFixture fixture = fixture(malformed);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
        assertTrue(failure.getMessage().contains("fases o las 36 clases"));
    }

    @Test
    void rejectsSignedManifestWhenAnyRequiredModelClassIsAbsent() throws Exception {
        String malformed = readyManifest().replace(
            "ESPUMA_VISIBLE_PALMA_IZQUIERDA", "UNKNOWN_SOAP_LABEL");
        ReleaseFixture fixture = fixture(malformed);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
        assertTrue(failure.getMessage().contains("fases o las 36 clases"));
    }

    @Test
    void rejectsSignedManifestWhenModelClassOrderDoesNotMatchCheckpointClassIds() throws Exception {
        String malformed = readyManifest().replace(
            "\"modelClassNames\":[\"OMS_01_MOJAR_MANOS\",\"OMS_02_APLICAR_JABON\"",
            "\"modelClassNames\":[\"OMS_02_APLICAR_JABON\",\"OMS_01_MOJAR_MANOS\"");
        ReleaseFixture fixture = fixture(malformed);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
        assertTrue(failure.getMessage().contains("fases o las 36 clases"));
    }

    @Test
    void rejectsActiveCheckpointChangedAfterReleaseSigning() throws Exception {
        ReleaseFixture fixture = fixture(readyManifest());
        Files.writeString(fixture.modelArtifact(), "checkpoint changed after signing");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
        assertTrue(failure.getMessage().contains("checkpoint activo"));
    }

    @Test
    void rejectsTrainingDataConfigChangedAfterReleaseSigning() throws Exception {
        ReleaseFixture fixture = fixture(readyManifest());
        Files.writeString(fixture.projectRoot().resolve("backend/models/test-data.yaml"),
            "names: [changed after signing]\n");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
        assertTrue(failure.getMessage().contains("data.yaml exacto"));
    }

    @Test
    void rejectsTrainingConfigWithClassOrderDifferentFromSignedTaxonomy() throws Exception {
        String mismatchedConfig = canonicalTrainingConfig().replace(
            "0: OMS_01_MOJAR_MANOS", "0: OMS_02_APLICAR_JABON");
        ReleaseFixture fixture = fixture(readyManifest(), mismatchedConfig);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
        assertTrue(failure.getMessage().contains("data.yaml exacto"));
    }

    @Test
    void rejectsTrainingConfigWithDuplicateClassIndexes() throws Exception {
        String duplicateIndexConfig = canonicalTrainingConfig().replace(
            "  1: OMS_02_APLICAR_JABON", "  0: OMS_02_APLICAR_JABON");
        ReleaseFixture fixture = fixture(readyManifest(), duplicateIndexConfig);

        assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
    }

    @Test
    void acceptsTrainingConfigWithCanonicalClassList() throws Exception {
        String listConfig = "path: .\ntrain: images/train\nval: images/val\nnames:\n"
            + requiredModelClasses().stream().map(name -> "  - " + name + "\n")
                .collect(java.util.stream.Collectors.joining());
        ReleaseFixture fixture = fixture(readyManifest(), listConfig);

        assertDoesNotThrow(() -> StationReleaseManifestVerifier.requireAuthorizedRelease(
            fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
    }

    @Test
    void rejectsTrainingDataConfigPathOutsideProject() throws Exception {
        String malformed = readyManifest().replace(
            "backend/models/test-data.yaml", "../outside-data.yaml");
        ReleaseFixture fixture = fixture(malformed);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
        assertTrue(failure.getMessage().contains("data.yaml exacto"));
    }

    @Test
    void rejectsTrainingTaskThatContradictsActiveDetector() throws Exception {
        String malformed = readyManifest().replace(
            "\"trainingRun\":{\"task\":\"detect\"",
            "\"trainingRun\":{\"task\":\"classify\"");
        ReleaseFixture fixture = fixture(malformed);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
        assertTrue(failure.getMessage().contains("checkpoint activo"));
    }

    @Test
    void rejectsStringThatLooksLikeBooleanReleaseEvidence() throws Exception {
        String malformed = readyManifest().replace("\"hospitalUseAllowed\":true",
            "\"hospitalUseAllowed\":\"true\"");
        ReleaseFixture fixture = fixture(malformed);

        assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
    }

    @Test
    void rejectsBlankBlockerEntriesInsteadOfTreatingThemAsAnEmptyList() throws Exception {
        String malformed = readyManifest().replace("\"blockers\":[]", "\"blockers\":[\"   \"]");
        ReleaseFixture fixture = fixture(malformed);

        assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
    }

    @Test
    void rejectsNonStringBlockerEntries() throws Exception {
        String malformed = readyManifest().replace("\"blockers\":[]", "\"blockers\":[null]");
        ReleaseFixture fixture = fixture(malformed);

        assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
    }

    @Test
    void rejectsPublicKeyStoredInsideProject() throws Exception {
        ReleaseFixture fixture = fixture(readyManifest());
        Path copiedKey = fixture.projectRoot().resolve("release-public.pem");
        Files.copy(fixture.publicKey(), copiedKey);

        assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), copiedKey.toString(), fixture.javaJar()));
    }

    @Test
    void rejectsJavaJarThatDiffersFromSignedReleaseDigest() throws Exception {
        ReleaseFixture fixture = fixture(readyManifest());
        Files.writeString(fixture.javaJar(), "modified after release signature");

        assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
    }

    @Test
    void rejectsRunningCopyOfJarEvenWhenItsBytesMatchTheSignedArtifact() throws Exception {
        ReleaseFixture fixture = fixture(readyManifest());
        Path copiedJar = temporaryDirectory.resolve("unapproved-copy.jar");
        Files.copy(fixture.javaJar(), copiedJar);

        assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), copiedJar));
    }

    @Test
    void rejectsRuntimeCameraScriptThatDiffersFromSignedReleaseDigest() throws Exception {
        ReleaseFixture fixture = fixture(readyManifest());
        Files.writeString(fixture.cameraScript(), "modified camera producer");

        assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
    }

    @Test
    void rejectsCameraRuntimeLockThatDiffersFromSignedReleaseDigest() throws Exception {
        ReleaseFixture fixture = fixture(readyManifest());
        Files.writeString(fixture.cameraRequirementsLock(), "opencv-python==0.0.0\n");

        assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
    }

    @Test
    void rejectsSbomGeneratorLockThatDiffersFromSignedReleaseDigest() throws Exception {
        ReleaseFixture fixture = fixture(readyManifest());
        Files.writeString(fixture.sbomGeneratorLock(), "modified generator lock");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
        assertTrue(failure.getMessage().contains("locks de runtime/generador"));
    }

    @Test
    void rejectsSignedManifestWhenJavaSbomChangesAfterSigning() throws Exception {
        ReleaseFixture fixture = fixture(readyManifest());
        Files.writeString(fixture.javaSbom(), "tampered SBOM");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
        assertTrue(failure.getMessage().contains("SBOM Java"));
    }

    @Test
    void rejectsSignedNonCycloneDxJavaSbomEvenWhenItsHashIsCorrect() throws Exception {
        ReleaseFixture fixture = fixture(readyManifest(), canonicalTrainingConfig(),
            "{\"bomFormat\":\"Other\",\"specVersion\":\"1.6\",\"version\":1,"
                + "\"metadata\":{\"component\":{\"type\":\"application\",\"name\":\"test\"}},"
                + "\"components\":[{\"type\":\"library\",\"name\":\"dep\",\"version\":\"1\"}]}");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
        assertTrue(failure.getMessage().contains("SBOM Java"));
    }

    @Test
    void rejectsJavaSbomWithNonObjectComponentEvenWhenItsHashIsCorrect() throws Exception {
        ReleaseFixture fixture = fixture(readyManifest(), canonicalTrainingConfig(),
            "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"version\":1,"
                + "\"metadata\":{\"component\":{\"type\":\"application\",\"name\":\"test\"}},"
                + "\"components\":[\"not-a-component-object\"]}");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
        assertTrue(failure.getMessage().contains("SBOM Java"));
    }

    @Test
    void rejectsJavaSbomVersionOutsideSigned64BitRange() throws Exception {
        String oversizedVersion = canonicalJavaSbom().replace(
            "\"version\":1", "\"version\":9223372036854775808");
        ReleaseFixture fixture = fixture(readyManifest(), canonicalTrainingConfig(), oversizedVersion);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
        assertTrue(failure.getMessage().contains("SBOM Java"));
    }

    @Test
    void rejectsCameraPythonSbomThatDoesNotMatchTheExactRuntimeLock() throws Exception {
        String wrongDependency = canonicalCameraPythonSbom().replace("opencv-python", "other-package");
        ReleaseFixture fixture = fixture(readyManifest(), canonicalTrainingConfig(),
            canonicalJavaSbom(), wrongDependency);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
        assertTrue(failure.getMessage().contains("SBOM Python de cámara"));
    }

    @Test
    void rejectsCameraPythonSbomWhenLockedVersionDoesNotMatch() throws Exception {
        String wrongVersion = canonicalCameraPythonSbom().replace("5.0.0.93", "5.0.0.92");
        ReleaseFixture fixture = fixture(readyManifest(), canonicalTrainingConfig(),
            canonicalJavaSbom(), wrongVersion);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> StationReleaseManifestVerifier.requireAuthorizedRelease(
                fixture.projectRoot(), fixture.publicKey().toString(), fixture.javaJar()));
        assertTrue(failure.getMessage().contains("SBOM Python de cámara"));
    }

    private ReleaseFixture fixture(String manifestJson) throws Exception {
        return fixture(manifestJson, canonicalTrainingConfig());
    }

    private ReleaseFixture fixture(String manifestJson, String trainingConfigContents) throws Exception {
        return fixture(manifestJson, trainingConfigContents, canonicalJavaSbom(), canonicalCameraPythonSbom());
    }

    private ReleaseFixture fixture(
        String manifestJson, String trainingConfigContents, String javaSbomContents) throws Exception {
        return fixture(manifestJson, trainingConfigContents, javaSbomContents, canonicalCameraPythonSbom());
    }

    private ReleaseFixture fixture(String manifestJson, String trainingConfigContents,
                                   String javaSbomContents, String cameraPythonSbomContents) throws Exception {
        Path project = temporaryDirectory.resolve("station-project");
        Path modelDirectory = project.resolve("backend/models");
        Files.createDirectories(modelDirectory);
        Path modelArtifact = modelDirectory.resolve("test-who-model.pt");
        Files.writeString(modelArtifact, "reviewed test OMS checkpoint");
        Path trainingConfig = modelDirectory.resolve("test-data.yaml");
        Files.writeString(trainingConfig, trainingConfigContents);
        Path movementIntentEvidence = modelDirectory.resolve("test-movement-intent-evidence.txt");
        Files.writeString(movementIntentEvidence,
            "Synthetic test fixture only; not clinical validation evidence.");
        Path javaJar = project.resolve("backend/target/hand-wash-compliance-1.0.0.jar");
        Files.createDirectories(javaJar.getParent());
        Files.writeString(javaJar, "reviewed test station jar");
        Path javaSbom = project.resolve("backend/target/handwash-java-sbom.json");
        Files.writeString(javaSbom, javaSbomContents);
        Path cameraPythonSbom = project.resolve("backend/target/handwash-camera-python-sbom.json");
        Files.writeString(cameraPythonSbom, cameraPythonSbomContents);
        Path supervisor = project.resolve("scripts/run_handwash_station.py");
        Files.createDirectories(supervisor.getParent());
        Files.writeString(supervisor, "reviewed test supervisor");
        Path cameraScript = project.resolve("scripts/run_yolo26_continuity_camera.py");
        Files.writeString(cameraScript, "reviewed test camera producer");
        Path cameraRequirementsLock = project.resolve("requirements-camera-macos-arm64.lock");
        Files.writeString(cameraRequirementsLock,
            "# Interpreter baseline: CPython 3.14.4\n# Platform baseline: macOS arm64\n"
                + "opencv-python==5.0.0.93\n");
        Path sbomGeneratorLock = project.resolve("requirements-sbom-generator-macos-arm64.lock");
        Files.writeString(sbomGeneratorLock, "cyclonedx-bom==7.5.0 --hash=sha256:" + "a".repeat(64) + "\n");
        Path manifest = modelDirectory.resolve("model-manifest.json");
        Path signatureFile = modelDirectory.resolve("model-manifest.json.sig");
        String boundManifest = manifestJson
            .replace("MODEL_HASH", sha256(modelArtifact))
            .replace("TRAINING_DATA_HASH", sha256(trainingConfig))
            .replace("MOVEMENT_INTENT_EVIDENCE_HASH", sha256(movementIntentEvidence))
            .replace("JAVA_ARTIFACT_HASH", sha256(javaJar))
            .replace("JAVA_SBOM_HASH", sha256(javaSbom))
            .replace("CAMERA_PYTHON_SBOM_HASH", sha256(cameraPythonSbom))
            .replace("SUPERVISOR_HASH", sha256(supervisor))
            .replace("CAMERA_PRODUCER_HASH", sha256(cameraScript))
            .replace("CAMERA_REQUIREMENTS_LOCK_HASH", sha256(cameraRequirementsLock))
            .replace("SBOM_GENERATOR_LOCK_HASH", sha256(sbomGeneratorLock));
        Files.writeString(manifest, boundManifest, StandardCharsets.UTF_8);

        Path keyDirectory = temporaryDirectory.resolve("trusted-release");
        Files.createDirectories(keyDirectory);
        Path publicKey = keyDirectory.resolve("release-public.pem");
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String pemBody = Base64.getMimeEncoder(64, new byte[] {'\n'})
            .encodeToString(pair.getPublic().getEncoded());
        Files.writeString(publicKey, "-----BEGIN PUBLIC KEY-----\n" + pemBody
            + "\n-----END PUBLIC KEY-----\n", StandardCharsets.US_ASCII);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(Files.readAllBytes(manifest));
        Files.write(signatureFile, signer.sign());
        return new ReleaseFixture(project, manifest, publicKey, javaJar, cameraScript,
            cameraRequirementsLock, javaSbom, sbomGeneratorLock, cameraPythonSbom, modelArtifact,
            trainingConfig);
    }

    private String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(path)));
    }

    private String canonicalTrainingConfig() {
        StringBuilder trainingYaml = new StringBuilder(
            "path: .\ntrain: images/train\nval: images/val\nnames:\n");
        List<String> classes = requiredModelClasses();
        for (int index = 0; index < classes.size(); index++) {
            trainingYaml.append("  ").append(index).append(": ")
                .append(classes.get(index)).append('\n');
        }
        return trainingYaml.toString();
    }

    private String canonicalJavaSbom() {
        return "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"version\":1,"
            + "\"metadata\":{\"component\":{\"type\":\"application\",\"name\":\"hand-wash-compliance\"}},"
            + "\"components\":[{\"type\":\"library\",\"name\":\"test-dependency\",\"version\":\"1.0.0\"}]}";
    }

    private String canonicalCameraPythonSbom() {
        return "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"version\":1,"
            + "\"components\":[{\"type\":\"library\",\"name\":\"opencv-python\","
            + "\"version\":\"5.0.0.93\"}]}";
    }

    private String readyManifest() {
        return "{\"schemaVersion\":1,"
            + "\"washingIntent\":{\"startUsesMovementMagnitudeThreshold\":true,"
            + "\"startMinimumNormalizedMovement\":1.0E-8,"
            + "\"stepMinimumNormalizedMovement\":1.0E-8,"
            + "\"startUsesClinicallyCalibratedMovementMagnitudeThreshold\":true,"
            + "\"stepCreditUsesMovementMagnitudeThreshold\":true,"
            + "\"stepCreditUsesClinicallyCalibratedMovementMagnitudeThreshold\":true,"
            + "\"validationEvidence\":{\"status\":\"VALIDATED_FOR_HOSPITAL_PILOT\","
            + "\"startIntentIndependentlyValidated\":true,"
            + "\"reportPath\":\"backend/models/test-movement-intent-evidence.txt\","
            + "\"reportSha256\":\"MOVEMENT_INTENT_EVIDENCE_HASH\"}},"
            + "\"deploymentReadiness\":{\"status\":\"READY_FOR_HOSPITAL_PILOT\","
            + "\"hospitalUseAllowed\":true,\"stepTaxonomyVerified\":true,"
            + "\"independentVideoValidationPassed\":true,\"continuityCameraPilotPassed\":true,"
            + "\"clinicalSafetyReviewPassed\":true,\"blockers\":[]},"
            + "\"stationArtifacts\":{" + "\"javaJarSha256\":\"JAVA_ARTIFACT_HASH\","
            + "\"javaSbomSha256\":\"JAVA_SBOM_HASH\","
            + "\"cameraPythonSbomSha256\":\"CAMERA_PYTHON_SBOM_HASH\","
            + "\"stationSupervisorSha256\":\"SUPERVISOR_HASH\","
            + "\"cameraProducerSha256\":\"CAMERA_PRODUCER_HASH\","
            + "\"cameraRequirementsLockSha256\":\"CAMERA_REQUIREMENTS_LOCK_HASH\","
            + "\"sbomGeneratorLockSha256\":\"SBOM_GENERATOR_LOCK_HASH\"},"
            + "\"active\":{\"path\":\"backend/models/test-who-model.pt\","
            + "\"task\":\"detect\",\"sha256\":\"MODEL_HASH\","
            + "\"modelClassNames\":" + jsonArray(requiredModelClasses()) + ","
            + "\"trainingRun\":{\"task\":\"detect\",\"trainingDataConfigIncluded\":true,"
            + "\"trainingDataConfigPath\":\"backend/models/test-data.yaml\","
            + "\"trainingDataConfigSha256\":\"TRAINING_DATA_HASH\","
            + "\"identicalToActiveCheckpoint\":true,\"checkpointSha256\":\"MODEL_HASH\"},"
            + "\"validatedAgainst\":{\"independentValidationEligible\":true}},"
            + "\"whoProcedure\":{\"status\":\"VALIDATED_FOR_HOSPITAL_PILOT\","
            + "\"approvalAllowed\":true,\"requiredPhases\":"
            + jsonArray(OmsAction.SECUENCIA.stream().map(Enum::name).toList()) + "}}";
    }

    private List<String> requiredModelClasses() {
        List<String> classes = new ArrayList<>();
        OmsAction.SECUENCIA.stream().map(OmsAction::getClaseModelo).forEach(classes::add);
        classes.add(OmsAction.CONTACTO_RIESGO.getClaseModelo());
        for (SoapRegion region : SoapRegion.values()) {
            classes.add(region.claseEspumaVisible());
            classes.add(region.claseSinEspumaVisible());
        }
        return classes;
    }

    private String jsonArray(List<String> values) {
        return "[" + values.stream().map(value -> "\"" + value + "\"")
            .collect(java.util.stream.Collectors.joining(",")) + "]";
    }

    private record ReleaseFixture(Path projectRoot, Path manifest, Path publicKey,
                                  Path javaJar, Path cameraScript, Path cameraRequirementsLock, Path javaSbom,
                                  Path sbomGeneratorLock, Path cameraPythonSbom, Path modelArtifact,
                                  Path trainingConfig) {}
}
