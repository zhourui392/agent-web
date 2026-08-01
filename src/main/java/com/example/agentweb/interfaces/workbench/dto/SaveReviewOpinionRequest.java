package com.example.agentweb.interfaces.workbench.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 保存 Review Opinion 的公开人工正文输入。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@Setter
@NoArgsConstructor
public final class SaveReviewOpinionRequest {

    @NotBlank
    @Size(max = 16000)
    private String content;

    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignoredValue) {
        throw new IllegalArgumentException(
                "unsupported review opinion field: " + field);
    }
}
