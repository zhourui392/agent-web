package com.example.agentweb.interfaces.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流保存请求。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-12
 */
@Getter
@Setter
public class WorkflowSaveRequest {

    @NotBlank
    private String name;
    private String description;
    private String agentType = "CODEX";
    @NotBlank
    private String workingDir;
    @Valid
    @NotEmpty
    private List<WorkflowStepRequest> steps = new ArrayList<>();
    private Boolean enabled = Boolean.TRUE;
}
