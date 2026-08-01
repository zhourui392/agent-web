package com.example.agentweb.interfaces.workbench.architecturefixture;

import com.example.agentweb.domain.workbench.WorkbenchRepository;

/**
 * ArchUnit 反例：Workbench Interface 不得绕过 Application 直接引用 Repository。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class WorkbenchRepositoryLeak {

    private WorkbenchRepository forbiddenDependency;
}
