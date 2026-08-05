package com.example.agentweb.domain.workbench;

import java.util.Optional;

/**
 * Dynamic Stage Run 私有最终 Prompt 的独立 Repository。
 *
 * @author alex
 * @since 2026-08-05
 */
public interface WorkbenchStageRunPromptPayloadRepository {

    void add(WorkbenchRunPromptPayload payload);

    Optional<WorkbenchRunPromptPayload> findByRunId(String runId);
}
