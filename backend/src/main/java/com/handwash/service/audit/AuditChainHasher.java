package com.handwash.service.audit;

import com.handwash.model.SessionAuditEvent;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Hash chain over the audit events. With a secret the link is an HMAC-SHA256, so someone who can
 * write to the database but does not hold the secret cannot recompute a consistent chain; without a
 * secret (local profile) it is a plain SHA-256 and the verifier reports the chain as unkeyed.
 * The event id is not hashed: ordering is carried by {@code prev_hash}.
 */
public final class AuditChainHasher {
    public static final String GENESIS = "0".repeat(64);
    private static final char SEP = '\u001f';
    private static final String NULL = "\u0000";
    private final byte[] key;

    public AuditChainHasher(String secret) {
        this.key = secret == null || secret.isBlank() ? null : secret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean keyed() { return key != null; }

    public String linkHash(String previousHash, SessionAuditEvent e) {
        return digest(previousHash + SEP + field(e.sessionId()) + SEP + field(e.eventType()) + SEP
            + field(e.actorSubject()) + SEP + field(e.stepCode()) + SEP + field(e.decisionCode()) + SEP
            + field(e.reasonCode()) + SEP + field(e.attemptNumber()) + SEP + field(e.frameSequence()) + SEP
            + field(e.confidence()) + SEP + e.occurredAtEpochMs());
    }

    /** Signs an arbitrary canonical text (seals) with the same key discipline as the chain. */
    public String sign(String canonical) { return digest("seal" + SEP + canonical); }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String digest(String text) {
        if (key == null) return sha256(text);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String field(Object value) { return value == null ? NULL : String.valueOf(value); }
}
