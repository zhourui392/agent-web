package com.example.agentweb.infra.workbench.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 直接从 Workbench 写侧事实读取当前活跃数量，避免进程重启后内存 Gauge 漂移。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
public final class WorkbenchActiveGauge {

    private static final String ACTIVE_COUNT_SQL =
            "SELECT COUNT(*) FROM workbench WHERE status='ACTIVE'";

    private final JdbcTemplate jdbc;

    public WorkbenchActiveGauge(
            MeterRegistry registry, JdbcTemplate jdbc) {
        Objects.requireNonNull(registry, "registry");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        Gauge.builder("workbench.active", this,
                        WorkbenchActiveGauge::activeCount)
                .register(registry);
    }

    private double activeCount() {
        Long count = jdbc.queryForObject(ACTIVE_COUNT_SQL, Long.class);
        return count == null ? 0.0D : count.doubleValue();
    }
}
