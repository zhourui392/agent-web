package com.example.agentweb.config;

import com.example.agentweb.config.capability.CapabilityCatalogProperties;
import com.example.agentweb.infra.capability.FileSystemMcpServerCatalog;
import com.example.agentweb.infra.harness.CodexHarnessRuntimeGateway;
import com.example.agentweb.infra.harness.FileSystemRuntimeEvidenceStore;
import com.example.agentweb.infra.harness.SqliteRuntimeExecutionQueryService;
import com.example.agentweb.infra.harness.SqliteRuntimeExecutionRepository;
import com.example.agentweb.interfaces.HarnessExecutionController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Harness Feature Flag 关闭时 M3 组件不进入 Spring 容器。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class HarnessM3ComponentFeatureFlagTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues("agent.harness.enabled=false")
            .withUserConfiguration(M3Components.class);

    @Test
    void disabledShouldKeepPublicCatalogButNotRegisterHarnessRuntimePersistenceOrApi() {
        runner.run(context -> {
            assertTrue(context.getBeansOfType(CodexHarnessRuntimeGateway.class).isEmpty());
            assertEquals(1, context.getBeansOfType(FileSystemMcpServerCatalog.class).size());
            assertTrue(context.getBeansOfType(FileSystemRuntimeEvidenceStore.class).isEmpty());
            assertTrue(context.getBeansOfType(SqliteRuntimeExecutionRepository.class).isEmpty());
            assertTrue(context.getBeansOfType(SqliteRuntimeExecutionQueryService.class).isEmpty());
            assertTrue(context.getBeansOfType(HarnessExecutionController.class).isEmpty());
        });
    }

    @Configuration
    @Import({CapabilityCatalogProperties.class,
            CodexHarnessRuntimeGateway.class, FileSystemMcpServerCatalog.class,
            FileSystemRuntimeEvidenceStore.class, SqliteRuntimeExecutionRepository.class,
            SqliteRuntimeExecutionQueryService.class, HarnessExecutionController.class})
    static class M3Components {
    }
}
