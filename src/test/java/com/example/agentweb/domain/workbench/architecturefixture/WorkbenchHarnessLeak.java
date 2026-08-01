package com.example.agentweb.domain.workbench.architecturefixture;

import com.example.agentweb.domain.harness.HarnessRunStatus;

/**
 * ArchUnit 反例：Workbench Domain 不得引用 Harness Domain。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class WorkbenchHarnessLeak {

    private HarnessRunStatus forbiddenDependency;
}
