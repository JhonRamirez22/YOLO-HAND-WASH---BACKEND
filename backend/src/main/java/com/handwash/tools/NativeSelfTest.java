package com.handwash.tools;

import org.bytedeco.opencv.opencv_core.Mat;

import static org.bytedeco.opencv.global.opencv_calib3d.RANSAC;
import static org.bytedeco.opencv.global.opencv_calib3d.estimateAffinePartial2D;
import static org.bytedeco.opencv.global.opencv_core.CV_32FC2;

/**
 * Loads the native OpenCV libraries the hand-motion estimator needs and runs one tiny estimate. It exists so CI can
 * prove, inside the real runtime image, that the natives load (a missing system library otherwise shows up only
 * when the first pose arrives). Run: {@code java -cp app.jar -Dloader.main=com.handwash.tools.NativeSelfTest
 * org.springframework.boot.loader.launch.PropertiesLauncher}.
 */
public final class NativeSelfTest {
    private NativeSelfTest() {}

    public static void main(String[] args) {
        try (Mat from = new Mat(4, 1, CV_32FC2); Mat to = new Mat(4, 1, CV_32FC2)) {
            org.bytedeco.javacpp.indexer.FloatIndexer a = from.createIndexer();
            org.bytedeco.javacpp.indexer.FloatIndexer b = to.createIndexer();
            float[][] points = {{0, 0}, {10, 0}, {0, 10}, {10, 10}};
            for (int i = 0; i < 4; i++) {
                a.put(i, 0, 0, points[i][0]);
                a.put(i, 0, 1, points[i][1]);
                b.put(i, 0, 0, points[i][0] + 2);
                b.put(i, 0, 1, points[i][1] + 3);
            }
            try (Mat transform = estimateAffinePartial2D(from, to, new Mat(), RANSAC, 4.0, 200L, 0.99, 10L)) {
                if (transform.empty()) throw new IllegalStateException("OpenCV returned an empty transform");
            }
            a.release();
            b.release();
            System.out.println("NATIVE_OK");
        } catch (Throwable failure) {
            System.err.println("NATIVE_FAILED " + failure);
            Throwable cause = failure.getCause();
            while (cause != null) {
                System.err.println("  caused by " + cause);
                cause = cause.getCause();
            }
            System.exit(1);
        }
    }
}
