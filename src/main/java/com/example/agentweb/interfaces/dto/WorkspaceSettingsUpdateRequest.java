package com.example.agentweb.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理后台工作空间配置更新请求。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
@Setter
public class WorkspaceSettingsUpdateRequest {

    @NotBlank
    private String defaultWorkspace;

    @NotEmpty
    private List<String> workspaceRoots = new ArrayList<String>();

    private List<String> uploadRoots = new ArrayList<String>();
}
