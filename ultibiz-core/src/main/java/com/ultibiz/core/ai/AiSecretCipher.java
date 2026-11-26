package com.ultibiz.core.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The API key is never stored in plain text: it is sealed with AES-GCM under a
 * key derived from the deployment secret. Without the secret the cipher
 * refuses to work, so a key can never end up unprotected.
 */
public final class AiSecretCipher {

    private static final String PREFIX = "v1:";
    private final SecretKeySpec key;

    public AiSecretCipher(String secret) {
        if (secret == null || secret.isBlank()) {
            this.key = null;
            return;
        }
        SecretKeySpec derived = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            derived = new SecretKeySpec(digest.digest(secret.getBytes(StandardCharsets.UTF_8)),
                    "AES");
        } catch (Exception ex) {
            derived = null;
        }
        this.key = derived;
    }

    public boolean isAvailable() {
        return key != null;
    }

    /** Seals a secret; without the deployment key the secret is not stored. */
    public String encrypt(String plain) {
        if (plain == null || plain.isBlank()) {
            return null;
        }
        if (key == null) {
            return null;
        }
        try {
            byte[] iv = new byte[12];
            new java.security.SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception ex) {
            return null;
        }
    }

    /** Opens a sealed secret; an unreadable value returns null. */
    public String decrypt(String sealed) {
        if (sealed == null || !sealed.startsWith(PREFIX) || key == null) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(sealed.substring(PREFIX.length()));
            byte[] iv = new byte[12];
            System.arraycopy(raw, 0, iv, 0, iv.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] plain = cipher.doFinal(raw, iv.length, raw.length - iv.length);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return null;
        }
    }
}
