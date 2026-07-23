package com.example.agentweb.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Capability Snapshot 选择、授权与 Prompt 输入请求。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
@Setter
public class HarnessCapabilityResolveRequest {

    private List<String> explicitSkillIds;
    private List<String> technicalTags;
    private List<String> approvedWorkspaceSkillIds;
    private List<String> readableFileRoots;
    private List<String> writableFileRoots;
    private List<String> executableCommands;
    private List<String> explicitMcpServerIds;
    private List<String> requiredMcpServerIds;
    private List<String> grantedMcpServerIds;

    @NotBlank
    private String upstreamArtifacts;

    @NotBlank
    private String currentInput;
}
