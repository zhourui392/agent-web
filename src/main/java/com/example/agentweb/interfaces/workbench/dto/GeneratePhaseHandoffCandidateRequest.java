package com.example.agentweb.interfaces.workbench.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.NoArgsConstructor;

/**
 * 从当前 Phase 公开会话生成 Candidate 的空命令体。
 *
 * <p>保留显式 JSON 对象是为了让未来扩展版本化；MVP 对任何未知字段 fail-closed。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@NoArgsConstructor
public final class GeneratePhaseHandoffCandidateRequest {

    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignoredValue) {
        throw new IllegalArgumentException(
                "unsupported handoff candidate field: " + field);
    }
}
