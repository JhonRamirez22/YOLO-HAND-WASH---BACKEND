package com.handwash.service.audit;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.regex.Pattern;

/**
 * On-premises destination of the daily seals: one new file per seal, never replaced, flushed to disk and made
 * read-only. It cannot match S3 Object Lock (whoever administers the machine can still alter the folder), so the
 * installer asks for a second copy of this directory somewhere else.
 */
public final class LocalFileAuditSealStore implements AuditSealStore {
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*");
    private final Path root;

    public LocalFileAuditSealStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] bytes) {
        if (key == null || !SAFE_KEY.matcher(key).matches() || key.contains("..")) {
            throw new IllegalArgumentException("Clave de sello inválida");
        }
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("Clave de sello fuera del directorio");
        try {
            Files.createDirectories(target.getParent());
            try (FileChannel channel = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            // Best effort: bind mounts from Windows (Docker Desktop) may refuse chmod; the seal is already stored.
            try {
                Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("r--r-----"));
            } catch (UnsupportedOperationException | IOException cannotChmod) {
                target.toFile().setReadOnly();
            }
        } catch (FileAlreadyExistsException exists) {
            throw new IllegalStateException("El sello ya existe y no se reemplaza", exists);
        } catch (IOException failure) {
            throw new IllegalStateException("No se pudo guardar el sello de auditoría", failure);
        }
    }

    @Override public boolean durable() { return true; }
}
