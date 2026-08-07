package com.example.agentweb.interfaces.workbench.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Workbench 创建请求，只接收用户选择，不接受客户端伪造的仓库真实路径或 Git 状态。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@Setter
public class CreateWorkbenchRequest {

    @NotBlank
    @Size(max = 512)
    private String title;

    @NotBlank
    @Size(max = 16000)
    private String originalGoal;

    @NotBlank
    @Size(max = 64)
    private String agentType;

    @Size(max = 256)
    private String environment;

    @NotBlank
    @Size(max = 4096)
    private String workspaceRoot;

    @NotBlank
    @Size(max = 512)
    private String primaryRepository;

    @Valid
    @NotEmpty
    private List<@NotBlank @Size(max = 512) String> repositories;

    @Valid
    @NotEmpty
    private List<@NotBlank @Size(max = 128) String> stageDefinitionIdentifiers;

    @Min(1)
    private long expectedStageCatalogVersion;

    private boolean useWorktree;
}
