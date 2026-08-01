package com.example.agentweb.infra.workbench.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Workbench 活跃数量 Gauge 的 SQLite 查询契约测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkbenchActiveGaugeTest {

    @Test
    void readsCurrentActiveWorkbenchCountWithoutCachingLifecycleState() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(jdbc.queryForObject(
                "SELECT COUNT(*) FROM workbench WHERE status='ACTIVE'",
                Long.class)).thenReturn(Long.valueOf(3L));

        new WorkbenchActiveGauge(registry, jdbc);

        assertEquals(3.0D, registry.get("workbench.active")
                .gauge().value());
    }
}
