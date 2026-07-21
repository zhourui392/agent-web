package com.example.agentweb.app.agentrun;

import lombok.Getter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * One assembled prompt part and its stable hash.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
@Getter
public class PromptPart {

    private final PromptPartType type;
    private final String title;
    private final String content;
    private final String metadataJson;
    private final String hash;

    public PromptPart(PromptPartType type, String title, String content, String metadataJson) {
        this.type = type;
        this.title = title;
        this.content = content == null ? "" : content;
        this.metadataJson = metadataJson;
        this.hash = sha256Hex(type + "\n" + title + "\n" + this.content + "\n" + metadataJson);
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
