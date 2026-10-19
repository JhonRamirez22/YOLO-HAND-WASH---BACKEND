package com.handwash.security.admin;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-256-GCM for the TOTP secrets at rest; the key lives outside the database (environment), like the audit chain secret. */
public final class TotpSecretVault {
    private static final int IV_BYTES = 12;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public TotpSecretVault(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length != 32) throw new IllegalStateException("La clave del cofre TOTP debe tener 32 bytes");
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(byte[] plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plain);
            byte[] joined = new byte[IV_BYTES + encrypted.length];
            System.arraycopy(iv, 0, joined, 0, IV_BYTES);
            System.arraycopy(encrypted, 0, joined, IV_BYTES, encrypted.length);
            return Base64.getEncoder().encodeToString(joined);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("No se pudo cifrar el secreto TOTP", failure);
        }
    }

    public byte[] decrypt(String stored) {
        try {
            byte[] joined = Base64.getDecoder().decode(stored);
            if (joined.length <= IV_BYTES) throw new IllegalStateException("Secreto TOTP corrupto");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, joined, 0, IV_BYTES));
            return cipher.doFinal(joined, IV_BYTES, joined.length - IV_BYTES);
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw new IllegalStateException("Secreto TOTP ilegible o alterado", failure);
        }
    }
}
