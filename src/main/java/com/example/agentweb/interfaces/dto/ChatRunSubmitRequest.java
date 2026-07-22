package com.example.agentweb.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Getter
@Setter
public class ChatRunSubmitRequest {

    @NotBlank
    private String message;

    private String resumeId;

    private Boolean recall;

    public boolean isRecallEnabled() {
        return recall == null || recall.booleanValue();
    }
}
