package com.example.agentweb.interfaces.workbench.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Workspace 检查请求。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@Setter
public class WorkspaceInspectRequest {

    @NotBlank
    @Size(max = 4096)
    private String workspaceRoot;
}
