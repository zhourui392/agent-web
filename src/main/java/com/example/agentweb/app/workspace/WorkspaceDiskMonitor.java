package com.example.agentweb.app.workspace;

import com.example.agentweb.app.requirement.RequirementProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.function.LongSupplier;

/**
 * 工作区磁盘监控（M3-lite）：容器 cgroup 强配额转条件触发后，以剩余空间日志替代——
 * 低于 {@code agent.requirement.workspace.min-free-disk-gb} 时打 warn 日志；ok→low 与恢复各记一次。
 * 监控是旁路：取数失败只降级，不卡主流程。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.requirement.enabled", havingValue = "true")
public class WorkspaceDiskMonitor {

    private static final long BYTES_PER_GB = 1024L * 1024 * 1024;

    private final RequirementProperties properties;
    private final LongSupplier freeBytesSupplier;

    private volatile boolean belowThreshold = false;

    @Autowired
    public WorkspaceDiskMonitor(RequirementProperties properties) {
        this(properties, defaultFreeBytes(properties));
    }

    WorkspaceDiskMonitor(RequirementProperties properties, LongSupplier freeBytesSupplier) {
        this.properties = properties;
        this.freeBytesSupplier = freeBytesSupplier;
    }

    @Scheduled(cron = "${agent.requirement.workspace.disk-check-cron:0 17 * * * *}")
    public void checkDiskSpace() {
        int minFreeGb = properties.getWorkspace().getMinFreeDiskGb();
        if (minFreeGb <= 0) {
            return;
        }
        long freeBytes = probeFreeBytes();
        if (freeBytes < 0) {
            return;
        }
        if (freeBytes >= minFreeGb * BYTES_PER_GB) {
            if (belowThreshold) {
                log.info("workspace-disk-recovered freeGb={}", freeBytes / BYTES_PER_GB);
            }
            belowThreshold = false;
            return;
        }
        belowThreshold = true;
        long freeGb = freeBytes / BYTES_PER_GB;
        log.warn("workspace-disk-low freeGb={} thresholdGb={} root={}",
                freeGb, minFreeGb, properties.getWorkspace().getRoot());
    }

    private long probeFreeBytes() {
        try {
            return freeBytesSupplier.getAsLong();
        } catch (RuntimeException e) {
            log.warn("workspace-disk-probe-failed reason={}", e.getMessage(), e);
            return -1L;
        }
    }

    /** 工作区根目录可能尚未创建，向上找最近存在的祖先测其可用空间；全不存在返回 -1 跳过本轮。 */
    private static LongSupplier defaultFreeBytes(RequirementProperties properties) {
        return () -> {
            File dir = new File(properties.getWorkspace().getRoot()).getAbsoluteFile();
            while (dir != null && !dir.exists()) {
                dir = dir.getParentFile();
            }
            return dir == null ? -1L : dir.getUsableSpace();
        };
    }
}
