package com.example.agentweb.infra.auth;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * Startup warning for deployments that leave admin-protected prefixes exposed by disabling admin auth.
 *
 * @author codex
 * @since 2026-06-12
 */
@Component
@Slf4j
public class AdminAuthExposureWarning {

    private final AdminProperties properties;
    private final Consumer<String> warningSink;

    @Autowired
    public AdminAuthExposureWarning(AdminProperties properties) {
        this(properties, log::warn);
    }

    AdminAuthExposureWarning(AdminProperties properties, Consumer<String> warningSink) {
        this.properties = properties;
        this.warningSink = warningSink;
    }

    @PostConstruct
    public void warnIfAdminDisabled() {
        if (!properties.isEnabled()) {
            warningSink.accept("admin-auth-disabled-protected-prefixes-exposed prefixes="
                    + properties.getProtectedPrefixes());
        }
    }
}
