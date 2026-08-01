package com.example.agentweb.domain.workbench;

import java.util.Optional;

/**
 * 下游阶段当前接收的 Handoff 版本写侧 Repository。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface HandoffReceptionRepository {

    void save(HandoffReception reception);

    Optional<HandoffReception> find(WorkbenchId workbenchId,
                                    WorkbenchPhase targetPhase,
                                    WorkbenchPhase sourcePhase);
}
