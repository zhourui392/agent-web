package com.example.agentweb.domain.workbench;

import java.util.Optional;

/**
 * Workbench Run 私有最终 Prompt 的写侧 Repository。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface WorkbenchRunPromptPayloadRepository {

    void add(WorkbenchRunPromptPayload payload);

    Optional<WorkbenchRunPromptPayload> findByRunId(String runId);
}
