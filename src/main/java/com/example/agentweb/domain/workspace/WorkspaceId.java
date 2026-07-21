package com.example.agentweb.domain.workspace;

import lombok.Value;

/**
 * 工作区 ID：'W' + 需求号后缀（R2607040001 → W2607040001），与需求一一对应便于互查。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Value
public class WorkspaceId {

    String value;

    public static WorkspaceId newId(String requirementId) {
        if (requirementId == null || requirementId.trim().isEmpty()) {
            throw new IllegalArgumentException("requirementId required");
        }
        String trimmed = requirementId.trim();
        return new WorkspaceId("W" + trimmed.substring(1));
    }
}
