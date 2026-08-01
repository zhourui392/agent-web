package com.example.agentweb.app.runtime.port;

import lombok.Getter;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Runtime 的主仓库、可读/可写真实根和沙箱边界。
 *
 * <p>本值对象只验证完整计划的技术不变量，不访问文件系统。调用方必须传入已由 Workspace
 * 防腐层解析的真实绝对规范路径。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkspaceLayout {

    private final String workspaceRoot;
    private final String primaryRepositoryRoot;
    private final List<String> readableRoots;
    private final List<String> writableRoots;
    private final SandboxMode sandboxMode;

    public WorkspaceLayout(String primaryRepositoryRoot,
                           List<String> readableRoots,
                           List<String> writableRoots,
                           SandboxMode sandboxMode) {
        this(inferWorkspaceRoot(primaryRepositoryRoot, readableRoots),
                primaryRepositoryRoot, readableRoots, writableRoots,
                sandboxMode);
    }

    public WorkspaceLayout(String workspaceRoot,
                           String primaryRepositoryRoot,
                           List<String> readableRoots,
                           List<String> writableRoots,
                           SandboxMode sandboxMode) {
        this.workspaceRoot = requireNormalizedAbsolute(
                workspaceRoot, "workspace root");
        this.primaryRepositoryRoot = requireNormalizedAbsolute(
                primaryRepositoryRoot, "primary repository root");
        this.readableRoots = immutableRoots(readableRoots, "readable roots", false);
        this.writableRoots = immutableRoots(writableRoots, "writable roots", true);
        this.sandboxMode = Objects.requireNonNull(sandboxMode, "sandboxMode");
        if (!this.readableRoots.contains(this.primaryRepositoryRoot)) {
            throw new IllegalArgumentException(
                    "primary repository root must be included in readable roots");
        }
        for (String root : this.readableRoots) {
            if (!Paths.get(root).startsWith(Paths.get(this.workspaceRoot))) {
                throw new IllegalArgumentException(
                        "readable repository roots must remain inside workspace root");
            }
        }
        if (sandboxMode == SandboxMode.READ_ONLY && !this.writableRoots.isEmpty()) {
            throw new IllegalArgumentException("read-only sandbox must not contain writable roots");
        }
        if (sandboxMode == SandboxMode.WORKSPACE_WRITE && this.writableRoots.isEmpty()) {
            throw new IllegalArgumentException(
                    "workspace-write sandbox must contain at least one writable root");
        }
        if (!this.readableRoots.containsAll(this.writableRoots)) {
            throw new IllegalArgumentException("writable roots must be a subset of readable roots");
        }
    }

    private static String inferWorkspaceRoot(
            String primaryRepositoryRoot, List<String> readableRoots) {
        Path common = Paths.get(requireNormalizedAbsolute(
                primaryRepositoryRoot, "primary repository root"));
        if (readableRoots != null) {
            for (String root : readableRoots) {
                Path candidate = Paths.get(requireNormalizedAbsolute(
                        root, "readable roots"));
                while (common != null && !candidate.startsWith(common)) {
                    common = common.getParent();
                }
            }
        }
        if (common == null) {
            throw new IllegalArgumentException(
                    "repository roots do not share a workspace boundary");
        }
        if (readableRoots != null && readableRoots.size() == 1
                && common.getParent() != null) {
            return common.getParent().toString();
        }
        return common.toString();
    }

    private static List<String> immutableRoots(
            List<String> roots, String name, boolean emptyAllowed) {
        if (roots == null || roots.contains(null) || (!emptyAllowed && roots.isEmpty())) {
            throw new IllegalArgumentException(name + " must be complete");
        }
        Set<String> normalized = new LinkedHashSet<String>();
        for (String root : roots) {
            normalized.add(requireNormalizedAbsolute(root, name));
        }
        if (normalized.size() != roots.size()) {
            throw new IllegalArgumentException(name + " must not contain duplicates");
        }
        return Collections.unmodifiableList(new ArrayList<String>(normalized));
    }

    private static String requireNormalizedAbsolute(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        try {
            Path path = Paths.get(value);
            if (!path.isAbsolute() || !path.normalize().equals(path)) {
                throw new IllegalArgumentException(
                        name + " must be an absolute normalized path");
            }
            return path.toString();
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException(name + " is invalid", ex);
        }
    }
}
