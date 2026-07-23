package com.example.agentweb.domain.harness;

import lombok.Getter;

/**
 * 固定 Prompt 装配所需的全部显式输入。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class HarnessPromptAssemblyRequest {

    private final String platformSafety;
    private final String environmentGuardrail;
    private final String stageContract;
    private final PromptPack promptPack;
    private final SkillSelection skillSelection;
    private final String upstreamArtifacts;
    private final String currentInput;

    public HarnessPromptAssemblyRequest(String platformSafety, String environmentGuardrail,
                                        String stageContract, PromptPack promptPack,
                                        SkillSelection skillSelection, String upstreamArtifacts,
                                        String currentInput) {
        this.platformSafety = DomainText.require(platformSafety, "platform safety rules");
        this.environmentGuardrail = DomainText.require(environmentGuardrail, "environment guardrail");
        this.stageContract = DomainText.require(stageContract, "stage contract");
        if (promptPack == null || skillSelection == null) {
            throw new IllegalArgumentException("prompt pack and skill selection must not be null");
        }
        this.promptPack = promptPack;
        this.skillSelection = skillSelection;
        this.upstreamArtifacts = DomainText.require(upstreamArtifacts, "upstream artifacts");
        this.currentInput = DomainText.require(currentInput, "current input");
    }
}
