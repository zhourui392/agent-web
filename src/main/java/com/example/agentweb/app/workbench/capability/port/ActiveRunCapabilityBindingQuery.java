package com.example.agentweb.app.workbench.capability.port;

import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;

import java.util.Optional;

/**
 * 查询 Phase 当前活动 Run 已冻结的 Capability Binding Hash。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface ActiveRunCapabilityBindingQuery {

    Optional<String> findActiveBindingHash(
            WorkbenchId workbenchId, WorkbenchPhase phase);
}
