package com.example.agentweb.app.refinery;

/**
 * Status of one chat recall attempt.
 *
 * @author codex
 * @since 2026-06-12
 */
public enum RecallStatus {
    PENDING,
    SKIPPED,
    NO_HIT,
    HIT,
    ERROR
}
