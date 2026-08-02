package com.example.agentweb.interfaces.workbench.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.NoArgsConstructor;

/**
 * 从当前 Review 公开会话生成 Candidate 的空命令体。
 *
 * <p>Candidate 没有采用、保存或确认参数；任何未知字段均 fail-closed。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@NoArgsConstructor
public final class GenerateReviewCandidateRequest {

    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignoredValue) {
        throw new IllegalArgumentException(
                "unsupported review candidate field: " + field);
    }
}
