package com.example.agentweb.domain.workspace.architecturefixture;

import com.example.agentweb.domain.harness.HarnessRunStatus;

/**
 * ArchUnit 反例：中性 Workspace Domain 不得引用 Harness Domain。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class WorkspaceHarnessLeak {

    private HarnessRunStatus forbiddenDependency;
}
