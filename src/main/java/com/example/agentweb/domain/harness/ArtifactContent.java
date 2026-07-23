package com.example.agentweb.domain.harness;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Artifact 正文值对象；构造时复制字节并固定大小和 SHA-256。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public final class ArtifactContent {

    private final byte[] bytes;
    private final long sizeBytes;
    private final String sha256;

    private ArtifactContent(byte[] bytes) {
        this.bytes = Arrays.copyOf(bytes, bytes.length);
        this.sizeBytes = bytes.length;
        this.sha256 = sha256(bytes);
    }

    public static ArtifactContent from(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("artifact content must not be null");
        }
        return new ArtifactContent(bytes);
    }

    public byte[] copyBytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    static String sha256(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value);
            StringBuilder encoded = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                encoded.append(String.format("%02x", item & 0xff));
            }
            return encoded.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
