package com.example.agentweb.domain.setting;

import lombok.Getter;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 工作空间运行配置。集中守护默认目录和授权根目录之间的不变量。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class WorkspaceSettings {

    private final String defaultWorkspace;
    private final List<String> workspaceRoots;
    private final List<String> uploadRoots;

    private WorkspaceSettings(String defaultWorkspace,
                              List<String> workspaceRoots,
                              List<String> uploadRoots) {
        this.defaultWorkspace = defaultWorkspace;
        this.workspaceRoots = workspaceRoots;
        this.uploadRoots = uploadRoots;
    }

    /**
     * 创建工作空间配置。
     *
     * @param defaultWorkspace 默认工作空间，必须属于 workspaceRoots
     * @param workspaceRoots   文件浏览和工作空间操作的授权根
     * @param uploadRoots      仅上传额外放行的授权根
     * @return 合法且路径已规范化的配置
     */
    public static WorkspaceSettings create(String defaultWorkspace,
                                           List<String> workspaceRoots,
                                           List<String> uploadRoots) {
        String normalizedDefault = normalizeAbsolute(defaultWorkspace, "Default workspace");
        List<String> normalizedWorkspaceRoots = normalizeRoots(workspaceRoots, "Workspace roots", false);
        List<String> normalizedUploadRoots = normalizeRoots(uploadRoots, "Upload roots", true);
        if (!normalizedWorkspaceRoots.contains(normalizedDefault)) {
            throw new IllegalArgumentException("Default workspace must be one of workspace roots");
        }
        return new WorkspaceSettings(normalizedDefault, normalizedWorkspaceRoots, normalizedUploadRoots);
    }

    /**
     * 面向目录选择器的有效根目录顺序：默认工作空间固定排在第一位。
     *
     * @return 不可变根目录列表
     */
    public List<String> effectiveWorkspaceRoots() {
        List<String> effective = new ArrayList<String>(workspaceRoots.size());
        effective.add(defaultWorkspace);
        for (String root : workspaceRoots) {
            if (!defaultWorkspace.equals(root)) {
                effective.add(root);
            }
        }
        return Collections.unmodifiableList(effective);
    }

    private static List<String> normalizeRoots(List<String> roots, String field, boolean allowEmpty) {
        if (roots == null || roots.isEmpty()) {
            if (allowEmpty) {
                return Collections.emptyList();
            }
            throw new IllegalArgumentException(field + " must not be empty");
        }
        List<String> normalized = new ArrayList<String>(roots.size());
        Set<String> unique = new LinkedHashSet<String>();
        for (String root : roots) {
            String path = normalizeAbsolute(root, field);
            if (!unique.add(path)) {
                throw new IllegalArgumentException(field + " contains duplicate path: " + path);
            }
            normalized.add(path);
        }
        return Collections.unmodifiableList(normalized);
    }

    private static String normalizeAbsolute(String rawPath, String field) {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        Path path = Paths.get(rawPath.trim());
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException(field + " must be absolute: " + rawPath);
        }
        return path.normalize().toString();
    }
}
