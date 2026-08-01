package com.example.agentweb.domain.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 跨限界上下文复用的稳定 SHA-256 与 canonical framing 工具。

 * @author alex
 * @since 2026-08-01
 */
public final class CanonicalHashing {

    private CanonicalHashing() {
    }

    public static String sha256(String content) {
        if (content == null) {
            throw new IllegalArgumentException("hash content must not be null");
        }
        return sha256(content.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] content) {
        if (content == null) {
            throw new IllegalArgumentException("hash content must not be null");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public static void appendFramed(StringBuilder canonical, String name, Object value) {
        String text = value == null ? "" : String.valueOf(value);
        canonical.append(name.length()).append(':').append(name)
                .append('=').append(text.length()).append(':').append(text).append('\n');
    }
}
