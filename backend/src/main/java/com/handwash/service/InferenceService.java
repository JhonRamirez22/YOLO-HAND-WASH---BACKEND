package com.handwash.service;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@ConditionalOnProperty(prefix = "handwash.inference", name = "api-enabled", havingValue = "true")
public class InferenceService {
    private static final Logger log = LoggerFactory.getLogger(InferenceService.class);

    @Value("${handwash.models.hand-detector:models/hand_detector.onnx}")
    private String handDetectorPath;

    @Value("${handwash.models.step-classifier:models/step_classifier.onnx}")
    private String stepClassifierPath;

    @Value("${handwash.inference.input-size:640}")
    private int inputSize;

    @Value("${handwash.inference.hand-confidence-threshold:0.25}")
    private float handConfidenceThreshold;

    @Value("${handwash.inference.step-confidence-threshold:0.15}")
    private float stepConfidenceThreshold;

    private final ImagePayloadValidator imagePayloadValidator;
    private final InferenceAdmissionGate inferenceAdmissionGate;

    private OrtEnvironment env;
    private OrtSession handSession;
    private OrtSession stepSession;

    public InferenceService(ImagePayloadValidator imagePayloadValidator,
                            InferenceAdmissionGate inferenceAdmissionGate) {
        this.imagePayloadValidator = imagePayloadValidator;
        this.inferenceAdmissionGate = inferenceAdmissionGate;
    }

    // NOTE: this mapping assumes class index order from the trained 8-class dataset.
    private static final Map<Integer, String> STEP_CLASSES = Map.of(
        0, "Paso1_Palmas",
        1, "Paso2_Dorsos",
        2, "Paso3_Interdigitales",
        3, "Paso4_Nudillos",
        4, "Paso5_Pulgar",
        5, "Paso6_PuntaDeDedos",
        6, "Paso7_Circulares",
        7, "Fondo"
    );

    private static final Map<String, String> STEP_TO_PASO = Map.of(
        "Paso1_Palmas", "PASO_1_PALMAS",
        "Paso2_Dorsos", "PASO_2_DORSOS",
        "Paso3_Interdigitales", "PASO_3_INTERDIGITALES",
        "Paso4_Nudillos", "PASO_4_NUDILLOS",
        "Paso5_Pulgar", "PASO_5_PULGAR",
        "Paso6_PuntaDeDedos", "PASO_6_PUNTA_DE_DEDOS",
        "Paso7_Circulares", "PASO_7_CIRCULARES",
        "Fondo", "FONDO"
    );

    @PostConstruct
    public void initialize() {
        try {
            env = OrtEnvironment.getEnvironment();
            handSession = tryCreateSession(handDetectorPath, "hand detector");
            stepSession = tryCreateSession(stepClassifierPath, "step classifier");
            log.info("Inference service initialized. handSessionLoaded={}, stepSessionLoaded={}",
                handSession != null, stepSession != null);
        } catch (Exception e) {
            log.error("Inference service degraded initialization: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void closeSessions() {
        for (OrtSession session : new OrtSession[]{handSession, stepSession}) {
            if (session == null) continue;
            try {
                session.close();
            } catch (Exception closeError) {
                log.warn("Error cerrando sesión ONNX: {}", closeError.getMessage());
            }
        }
    }

    public Map<String, Object> infer(byte[] imageBytes) {
        Map<String, Object> result = createDefaultResult();

        if (stepSession == null || env == null) {
            result.put("error", "Inference model(s) unavailable. Configure ONNX model paths to enable inference.");
            result.put("modelStatus", modelStatus());
            return result;
        }

        InferenceAdmissionGate.Permit permit = inferenceAdmissionGate.tryAcquire();
        if (permit == null) {
            result.put("error", "Local inference is at capacity");
            result.put("errorCode", "INFERENCE_BUSY");
            return result;
        }
        try (permit) {
            try {
                float[] preprocessed = preprocess(imageBytes);
                try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(preprocessed),
                    new long[]{1, 3, inputSize, inputSize})) {

                    // The trained 8-class model already contains hand bounding boxes.
                    // A separate hand detector is optional and only acts as a gate when present.
                    if (handSession != null) {
                        List<float[]> handDetections = runYoloStage(handSession, inputTensor, handConfidenceThreshold);
                        float[] bestHand = handDetections.stream()
                            .max(Comparator.comparingDouble(d -> d[1]))
                            .orElse(null);
                        if (bestHand != null) {
                            result.put("manoDetectada", true);
                            result.put("manoConfianza", round4(bestHand[1]));
                        }
                    }

                    List<float[]> stepDetections = runYoloStage(stepSession, inputTensor, stepConfidenceThreshold);
                    if (!stepDetections.isEmpty()) {
                        float[] bestStep = stepDetections.stream()
                            .max(Comparator.comparingDouble(d -> d[1]))
                            .orElse(null);

                        if (bestStep != null) {
                            int classId = (int) bestStep[0];
                            String className = STEP_CLASSES.getOrDefault(classId, "unknown");
                            result.put("pasoDetectado", className);
                            result.put("pasoConfianza", round4(bestStep[1]));
                            result.put("claseBackend", STEP_TO_PASO.get(className));
                            if ("Fondo".equals(className)) {
                                result.put("manoDetectada", false);
                            } else if (handSession == null) {
                                result.put("manoDetectada", true);
                                result.put("manoConfianza", round4(bestStep[1]));
                            }
                        }
                    }
                }

            } catch (Exception e) {
                log.error("Inference error: {}", e.getMessage());
                result.put("error", "Inference failed: " + e.getMessage());
            }
        }

        return result;
    }

    private Map<String, Object> createDefaultResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("manoDetectada", false);
        result.put("manoConfianza", null);
        result.put("pasoDetectado", null);
        result.put("pasoConfianza", null);
        result.put("claseBackend", null);
        return result;
    }

    private Map<String, Object> modelStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("handDetector", handSession != null ? "loaded" : "missing");
        status.put("stepClassifier", stepSession != null ? "loaded" : "missing");
        return status;
    }

    private OrtSession tryCreateSession(String configuredPath, String modelName) {
        try {
            byte[] modelBytes = readModelBytes(configuredPath);
            if (modelBytes == null) {
                log.warn("{} model not found at path '{}'. Inference remains optional.", modelName, configuredPath);
                return null;
            }
            return env.createSession(modelBytes, new OrtSession.SessionOptions());
        } catch (Exception e) {
            log.error("Failed to load {} model at '{}': {}", modelName, configuredPath, e.getMessage());
            return null;
        }
    }

    private byte[] readModelBytes(String configuredPath) throws IOException {
        if (configuredPath == null || configuredPath.isBlank()) {
            return null;
        }

        Path fsPath = Paths.get(configuredPath);
        if (Files.exists(fsPath)) {
            return Files.readAllBytes(fsPath);
        }

        // The application can be launched either from the repository root
        // (scripts/start_handwash.sh) or from backend/. Resolve the same
        // configured models/ path in both working directories.
        if (!fsPath.isAbsolute() && !configuredPath.startsWith("classpath:")) {
            Path repositoryRelativePath = Paths.get("backend").resolve(fsPath).normalize();
            if (Files.exists(repositoryRelativePath)) {
                return Files.readAllBytes(repositoryRelativePath);
            }
        }

        if (configuredPath.startsWith("classpath:")) {
            String cp = configuredPath.substring("classpath:".length());
            ClassPathResource resource = new ClassPathResource(cp);
            return resource.exists() ? resource.getInputStream().readAllBytes() : null;
        }

        ClassPathResource resource = new ClassPathResource(configuredPath);
        if (resource.exists()) {
            return resource.getInputStream().readAllBytes();
        }

        return null;
    }

    private List<float[]> runYoloStage(OrtSession session, OnnxTensor inputTensor, float confThresh) throws OrtException {
        Map<String, OnnxTensor> inputs = Map.of(resolveInputName(session), inputTensor);
        try (OrtSession.Result stageResult = session.run(inputs)) {
            Object outputValue = stageResult.get(0).getValue();
            return postprocessYolo(outputValue, confThresh);
        }
    }

    private String resolveInputName(OrtSession session) throws OrtException {
        return session.getInputNames().stream().findFirst().orElse("images");
    }

    private float[] preprocess(byte[] imageBytes) throws IOException {
        BufferedImage image = decodeImage(imageBytes);
        BufferedImage resized = new BufferedImage(inputSize, inputSize, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, inputSize, inputSize, null);
        g.dispose();

        int[] rgbPixels = resized.getRGB(0, 0, inputSize, inputSize, null, 0, inputSize);
        float[] chw = new float[3 * inputSize * inputSize];
        int planeSize = inputSize * inputSize;

        for (int i = 0; i < rgbPixels.length; i++) {
            int rgb = rgbPixels[i];
            chw[i] = ((rgb >> 16) & 0xFF) / 255.0f;
            chw[planeSize + i] = ((rgb >> 8) & 0xFF) / 255.0f;
            chw[(2 * planeSize) + i] = (rgb & 0xFF) / 255.0f;
        }
        return chw;
    }

    private BufferedImage decodeImage(byte[] bytes) throws IOException {
        return imagePayloadValidator.decode(bytes);
    }

    private List<float[]> postprocessYolo(Object output, float confThresh) {
        if (output instanceof float[][][] arr3 && arr3.length > 0) {
            return postprocessMatrix(arr3[0], confThresh);
        }
        if (output instanceof float[][] arr2) {
            return postprocessMatrix(arr2, confThresh);
        }

        float[] preds = flattenToFloatArray(output);
        if (preds.length == 0) {
            return List.of();
        }

        int numFeatures = inferFeatureCount(preds.length);
        if (numFeatures <= 4) {
            return List.of();
        }

        int numDetections = preds.length / numFeatures;
        List<float[]> detections = new ArrayList<>();

        for (int i = 0; i < numDetections; i++) {
            int offset = i * numFeatures;
            float cx = preds[offset];
            float cy = preds[offset + 1];
            float w = preds[offset + 2];
            float h = preds[offset + 3];

            float maxScore = 0;
            int classId = 0;
            for (int j = 4; j < numFeatures; j++) {
                float score = preds[offset + j];
                if (score > maxScore) {
                    maxScore = score;
                    classId = j - 4;
                }
            }

            if (maxScore >= confThresh) {
                float x1 = cx - w / 2;
                float y1 = cy - h / 2;
                float x2 = cx + w / 2;
                float y2 = cy + h / 2;
                detections.add(new float[]{classId, maxScore, x1, y1, x2, y2});
            }
        }

        return nms(detections, 0.45f);
    }

    /** Handles both ONNX layouts: (features, detections) and (detections, features). */
    private List<float[]> postprocessMatrix(float[][] matrix, float confThresh) {
        if (matrix.length == 0 || matrix[0].length == 0) return List.of();
        boolean featuresFirst = matrix.length <= 64 && matrix[0].length > matrix.length;
        int numFeatures = featuresFirst ? matrix.length : matrix[0].length;
        int numDetections = featuresFirst ? matrix[0].length : matrix.length;
        if (numFeatures <= 4) return List.of();

        List<float[]> detections = new ArrayList<>();
        for (int i = 0; i < numDetections; i++) {
            float cx = valueAt(matrix, featuresFirst, 0, i);
            float cy = valueAt(matrix, featuresFirst, 1, i);
            float w = valueAt(matrix, featuresFirst, 2, i);
            float h = valueAt(matrix, featuresFirst, 3, i);
            float maxScore = 0;
            int classId = 0;
            for (int feature = 4; feature < numFeatures; feature++) {
                float score = valueAt(matrix, featuresFirst, feature, i);
                if (score > maxScore) {
                    maxScore = score;
                    classId = feature - 4;
                }
            }
            if (maxScore >= confThresh) {
                detections.add(new float[]{
                    classId, maxScore,
                    cx - w / 2, cy - h / 2,
                    cx + w / 2, cy + h / 2
                });
            }
        }
        return nms(detections, 0.45f);
    }

    private float valueAt(float[][] matrix, boolean featuresFirst, int feature, int detection) {
        return featuresFirst ? matrix[feature][detection] : matrix[detection][feature];
    }

    private int inferFeatureCount(int valuesLength) {
        int[] preferred = {12, 11, 10, 9, 8};
        for (int candidate : preferred) {
            if (valuesLength % candidate == 0) {
                return candidate;
            }
        }
        return 11;
    }

    private float[] flattenToFloatArray(Object output) {
        if (output instanceof float[] arr) {
            return arr;
        }
        if (output instanceof float[][] arr2) {
            return arr2.length > 0 ? arr2[0] : new float[0];
        }
        if (output instanceof float[][][] arr3) {
            if (arr3.length == 0) return new float[0];
            List<Float> flat = new ArrayList<>();
            for (float[] row : arr3[0]) {
                for (float v : row) {
                    flat.add(v);
                }
            }
            float[] out = new float[flat.size()];
            for (int i = 0; i < flat.size(); i++) out[i] = flat.get(i);
            return out;
        }
        log.warn("Unsupported ONNX output type: {}", output == null ? "null" : output.getClass().getName());
        return new float[0];
    }

    private List<float[]> nms(List<float[]> detections, float iouThresh) {
        if (detections.isEmpty()) return detections;

        detections.sort((a, b) -> Float.compare(b[1], a[1]));
        List<float[]> result = new ArrayList<>();
        boolean[] suppressed = new boolean[detections.size()];

        for (int i = 0; i < detections.size(); i++) {
            if (suppressed[i]) continue;
            result.add(detections.get(i));
            for (int j = i + 1; j < detections.size(); j++) {
                if (suppressed[j]) continue;
                float iou = calculateIoU(detections.get(i), detections.get(j));
                if (iou > iouThresh) {
                    suppressed[j] = true;
                }
            }
        }
        return result;
    }

    private float calculateIoU(float[] a, float[] b) {
        float x1 = Math.max(a[2], b[2]);
        float y1 = Math.max(a[3], b[3]);
        float x2 = Math.min(a[4], b[4]);
        float y2 = Math.min(a[5], b[5]);

        float intersection = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
        float areaA = (a[4] - a[2]) * (a[5] - a[3]);
        float areaB = (b[4] - b[2]) * (b[5] - b[3]);
        float union = areaA + areaB - intersection;

        return union > 0 ? intersection / union : 0;
    }

    private double round4(float value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
