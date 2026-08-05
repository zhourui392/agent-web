package com.example.agentweb.config.workbench;

import com.example.agentweb.domain.capability.CapabilityArtifactRegistry;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCapabilityResolver;
import com.example.agentweb.infra.capability.SqliteCapabilityArtifactRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;

/**
 * Workbench Capability 应用端口装配。
 *
 * @author alex
 * @since 2026-08-01
 */
@Configuration
public class WorkbenchCapabilityConfiguration {

    @Bean
    public CapabilityArtifactRegistry capabilityArtifactRegistry(
            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
            WorkbenchCapabilityProperties properties, Clock clock) {
        return new SqliteCapabilityArtifactRegistry(
                jdbcTemplate, objectMapper,
                Path.of(properties.getArtifactRoot()), clock);
    }

    @Bean
    public WorkbenchStageCapabilityResolver workbenchStageCapabilityResolver(
            CapabilityArtifactRegistry artifactRegistry) {
        return new WorkbenchStageCapabilityResolver(artifactRegistry);
    }

}
