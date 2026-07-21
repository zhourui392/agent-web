package com.example.agentweb.infra.issuelog;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 按 {@code workingDir} 维度的进程内锁注册表。
 *
 * <p>保证同一工作目录下的 id 分配 + INDEX 追加在串行执行,
 * 避免两次并发 save 撞号。多实例部署时再升级为文件锁。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-19
 */
@Component
public class WorkingDirIssueLogLockRegistry {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ReentrantLock obtain(Path workingDir) {
        String key = workingDir.toAbsolutePath().normalize().toString();
        return locks.computeIfAbsent(key, k -> new ReentrantLock());
    }
}
