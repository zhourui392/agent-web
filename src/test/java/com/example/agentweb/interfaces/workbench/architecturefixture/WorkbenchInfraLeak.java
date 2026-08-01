package com.example.agentweb.interfaces.workbench.architecturefixture;

import com.example.agentweb.infra.workbench.SqliteWorkbenchRepository;

/**
 * ArchUnit 反例：Workbench Interface 不得直接引用 Infrastructure Adapter。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class WorkbenchInfraLeak {

    private SqliteWorkbenchRepository forbiddenDependency;
}
