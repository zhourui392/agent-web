package com.example.agentweb.app.workspace;

import com.example.agentweb.app.requirement.RequirementProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 工作区磁盘监控（M3-lite）：低于阈值只记日志（告警已随值班摘除）；阈值 0 = 关闭不探测；取数失败只降级不抛。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class WorkspaceDiskMonitorTest {

    private static final long GB = 1024L * 1024 * 1024;

    private RequirementProperties properties;

    @BeforeEach
    public void setUp() {
        properties = new RequirementProperties();
        properties.getWorkspace().setMinFreeDiskGb(10);
    }

    @Test
    public void check_should_not_probe_when_threshold_disabled() {
        properties.getWorkspace().setMinFreeDiskGb(0);
        WorkspaceDiskMonitor monitor = new WorkspaceDiskMonitor(properties, throwingSupplier());

        assertDoesNotThrow(monitor::checkDiskSpace);
    }

    @Test
    public void check_should_log_and_not_throw_when_below_threshold() {
        WorkspaceDiskMonitor monitor = new WorkspaceDiskMonitor(properties, () -> 3 * GB);

        assertDoesNotThrow(() -> {
            monitor.checkDiskSpace();
            monitor.checkDiskSpace();
        });
    }

    @Test
    public void check_should_swallow_supplier_failure() {
        WorkspaceDiskMonitor broken = new WorkspaceDiskMonitor(properties, () -> {
            throw new IllegalStateException("disk probe failed");
        });

        assertDoesNotThrow(broken::checkDiskSpace);
    }

    @Test
    public void check_should_skip_when_free_space_unknown() {
        WorkspaceDiskMonitor monitor = new WorkspaceDiskMonitor(properties, () -> -1L);

        assertDoesNotThrow(monitor::checkDiskSpace);
    }

    private LongSupplier throwingSupplier() {
        return () -> {
            throw new AssertionError("threshold=0 时不应取磁盘数据");
        };
    }
}
