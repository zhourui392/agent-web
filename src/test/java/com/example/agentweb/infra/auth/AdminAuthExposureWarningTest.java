package com.example.agentweb.infra.auth;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests startup warning when protected admin prefixes are configured but admin auth is disabled.
 *
 * @author codex
 * @since 2026-06-12
 */
class AdminAuthExposureWarningTest {

    @Test
    void warnIfAdminDisabled_shouldListProtectedPrefixes() {
        AdminProperties properties = new AdminProperties();
        properties.setEnabled(false);
        properties.setProtectedPrefixes(Arrays.asList("/api/metrics", "/api/diagnose-history"));
        AtomicReference<String> log = new AtomicReference<>();

        new AdminAuthExposureWarning(properties, log::set).warnIfAdminDisabled();

        assertTrue(log.get().contains("admin-auth-disabled-protected-prefixes-exposed"));
        assertTrue(log.get().contains("/api/metrics"));
        assertTrue(log.get().contains("/api/diagnose-history"));
    }
}
