package com.handwash.service;

import com.handwash.model.HandPoseEvidence;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in numeric microbenchmark: no image inference or recognition accuracy. */
class OpenCvHandMotionPerformanceBenchmark {
    public static void main(String[] args) {
        new OpenCvHandMotionPerformanceBenchmark().measureSameAndSwappedHandOrder();
    }
    @Test
    void measureSameAndSwappedHandOrder() {
        var estimator = new OpenCvHandMotionEstimator();
        var poses = new HandPoseEvidence[] {pose(0, false), pose(16, false), pose(0, true), pose(16, true)};
        int warmup = 500;
        int samples = 5000;
        double[] elapsedMs = new double[samples];
        double checksum = 0;
        for (int index = 0; index < warmup + samples; index++) {
            long started = System.nanoTime();
            var measured = estimator.observe("bench", "epoch", index + 1L,
                1_000_000_000L + index * 200_000_000L, poses[index % poses.length]);
            long elapsed = System.nanoTime() - started;
            if (index >= warmup) {
                assertTrue(measured.valid());
                elapsedMs[index - warmup] = elapsed / 1_000_000.0;
                checksum += measured.residualNormalized();
            }
        }
        assertTrue(checksum > 0);
        Arrays.sort(elapsedMs);
        System.out.printf(java.util.Locale.ROOT,
            "OPEN_CV_MOTION_BENCHMARK samples=%d p50_ms=%.6f p95_ms=%.6f checksum=%.9f%n",
            samples, elapsedMs[samples / 2], elapsedMs[(int)(samples * 0.95)], checksum);
    }

    private static HandPoseEvidence pose(double shift, boolean swap) {
        Double[][][] points = new Double[2][21][3];
        Double[][] boxes = new Double[2][4];
        for (int hand = 0; hand < 2; hand++) {
            int target = swap ? 1 - hand : hand;
            double offset = hand == 0 ? shift : 0;
            for (int point = 0; point < 21; point++) {
                points[target][point] = new Double[] {100.0 + hand * 65 + (point % 5) * 10,
                    100.0 + offset + (point / 5) * 10, 0.95};
            }
            boxes[target] = new Double[] {96.0 + hand * 65, 96.0 + offset, 144.0 + hand * 65, 144.0 + offset};
        }
        return new HandPoseEvidence(points, boxes, 640, 480);
    }
}
