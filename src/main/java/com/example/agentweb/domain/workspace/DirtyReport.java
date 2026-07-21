package com.example.agentweb.domain.workspace;

import lombok.Value;

import java.util.Collections;
import java.util.List;

/**
 * 工作区脏态检测报告：未提交文件 + 本地未交付提交数，任一非空即 dirty。
 * 是"某一时刻的事实"而非生命周期状态，仅供 {@code assertReleasable} 不变量消费。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Value
public class DirtyReport {

    List<String> uncommittedFiles;
    int unpushedCommits;

    public DirtyReport(List<String> uncommittedFiles, int unpushedCommits) {
        this.uncommittedFiles = uncommittedFiles == null
                ? Collections.emptyList()
                : List.copyOf(uncommittedFiles);
        this.unpushedCommits = unpushedCommits;
    }

    public static DirtyReport clean() {
        return new DirtyReport(Collections.emptyList(), 0);
    }

    public boolean isDirty() {
        return !uncommittedFiles.isEmpty() || unpushedCommits > 0;
    }
}
