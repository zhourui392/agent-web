package com.example.agentweb.domain.mode;

/**
 * 模式快照与不可变 Artifact Registry 不再匹配（能力被归档/内容变化）。
 *
 * @author alex
 * @since 2026-08-20
 */
public class ModeCapabilityResolutionException extends RuntimeException {

    public ModeCapabilityResolutionException(String message) {
        super(message);
    }
}
