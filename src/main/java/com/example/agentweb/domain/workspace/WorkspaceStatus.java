package com.example.agentweb.domain.workspace;

/**
 * 工作区生命周期：PROVISIONING → READY ⇄ IN_USE → RELEASED（终态）。
 * DIRTY 不是状态——那是 {@link DirtyReport} 承载的某一时刻检测事实（detailed-design §2.1）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public enum WorkspaceStatus {

    PROVISIONING,
    READY,
    IN_USE,
    RELEASED
}
