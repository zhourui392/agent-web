package com.example.agentweb.domain.harness;

/**
 * Harness 领域对象的稳定 SHA-256 兼容入口。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public final class HarnessHashing {

    private HarnessHashing() {
    }

    public static String sha256(String content) {
        return com.example.agentweb.domain.shared.CanonicalHashing.sha256(content);
    }

    public static String sha256(byte[] content) {
        return com.example.agentweb.domain.shared.CanonicalHashing.sha256(content);
    }

    public static void appendFramed(StringBuilder canonical, String name, Object value) {
        com.example.agentweb.domain.shared.CanonicalHashing.appendFramed(
                canonical, name, value);
    }
}
