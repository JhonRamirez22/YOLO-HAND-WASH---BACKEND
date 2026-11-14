package com.handwash.service.evidence;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Shared fixtures: real JPEGs and an in-memory encrypted-store stand-in. */
final class EvidenceTestSupport {
    static { System.setProperty("java.awt.headless", "true"); }

    private EvidenceTestSupport() {}

    static byte[] jpeg(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(40, 120, 200), width, height, new Color(230, 180, 60)));
        g.fillRect(0, 0, width, height);
        g.setColor(Color.WHITE);
        for (int i = 0; i < 40; i++) g.drawLine(i * width / 40, 0, width - i * width / 40, height);
        g.dispose();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", out);
            return out.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static final class InMemoryStore implements EvidenceStore {
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        boolean failPut;
        boolean failDelete;

        @Override public void put(String key, byte[] bytes) {
            if (failPut) throw new EvidenceStoreException("put failed");
            objects.put(key, bytes);
        }
        @Override public Optional<byte[]> get(String key) { return Optional.ofNullable(objects.get(key)); }
        @Override public void delete(String key) {
            if (failDelete) throw new EvidenceStoreException("delete failed");
            objects.remove(key);
        }
    }
}
