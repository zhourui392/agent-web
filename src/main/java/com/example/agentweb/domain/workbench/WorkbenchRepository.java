package com.example.agentweb.domain.workbench;

import java.util.Optional;

/**
 * Workbench 写侧 Repository，仅暴露聚合生命周期。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface WorkbenchRepository {

    void add(Workbench workbench);

    Optional<Workbench> findById(WorkbenchId workbenchId);

    void update(Workbench workbench);
}
