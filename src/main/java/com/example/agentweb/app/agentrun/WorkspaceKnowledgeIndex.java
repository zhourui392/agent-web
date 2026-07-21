package com.example.agentweb.app.agentrun;

import lombok.Getter;

import java.nio.file.Path;

/**
 * A lightweight workspace knowledge index discovered from workingDir.
 *
 * <p>{@code topK <= 0} 表示未显式配置, 召回时回落 policy 全局值;
 * {@code mode} 区分指针注入 (POINTER, 只注入命中条目的文件路径) 与
 * 事实直注 (INLINE, 命中行整行注入, 用于服务导航表等确定性映射).</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
@Getter
public class WorkspaceKnowledgeIndex {

    /** 索引注入形态. */
    public enum Mode {
        /** 只注入命中条目的文件路径, 正文由 agent 按需 Read. */
        POINTER,
        /** 命中行整行直注 (事实映射, 无带偏风险). */
        INLINE
    }

    private final String name;
    private final Path path;
    private final String relativePath;
    private final int topK;
    private final Mode mode;

    public WorkspaceKnowledgeIndex(String name, Path path, String relativePath, int topK) {
        this(name, path, relativePath, topK, Mode.POINTER);
    }

    public WorkspaceKnowledgeIndex(String name, Path path, String relativePath, int topK, Mode mode) {
        this.name = name;
        this.path = path;
        this.relativePath = relativePath;
        this.topK = Math.max(topK, 0);
        this.mode = mode == null ? Mode.POINTER : mode;
    }
}
