package com.example.agentweb.domain.harness;

import java.util.ArrayList;
import java.util.List;

/**
 * Harness 首版固定顺序 Prompt 装配器。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public final class HarnessPromptAssembler {

    public HarnessPromptAssembly assemble(HarnessPromptAssemblyRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("prompt assembly request must not be null");
        }
        PromptPack pack = request.getPromptPack();
        List<HarnessPromptPart> parts = new ArrayList<HarnessPromptPart>();
        parts.add(HarnessPromptPart.from(PromptPartType.PLATFORM_SAFETY,
                "platform-policy", request.getPlatformSafety()));
        parts.add(HarnessPromptPart.from(PromptPartType.ENVIRONMENT_GUARDRAIL,
                "environment-policy", request.getEnvironmentGuardrail()));
        parts.add(HarnessPromptPart.from(PromptPartType.STAGE_CONTRACT,
                "stage-contract", request.getStageContract()));
        addPackPart(parts, PromptPartType.STAGE_SYSTEM, pack, PromptResourceRole.SYSTEM);
        addPackPart(parts, PromptPartType.STAGE_TASK, pack, PromptResourceRole.TASK);
        addPackPart(parts, PromptPartType.STAGE_GATE_HINTS, pack, PromptResourceRole.GATE_HINTS);
        parts.add(HarnessPromptPart.from(PromptPartType.SELECTED_SKILLS,
                "skill-selection", selectedSkillInstructions(request.getSkillSelection())));
        parts.add(HarnessPromptPart.from(PromptPartType.UPSTREAM_ARTIFACTS,
                "approved-upstream-artifacts", request.getUpstreamArtifacts()));
        parts.add(HarnessPromptPart.from(PromptPartType.CURRENT_INPUT,
                "current-input", request.getCurrentInput()));
        addPackPart(parts, PromptPartType.OUTPUT_CONTRACT, pack, PromptResourceRole.OUTPUT_CONTRACT);

        StringBuilder prompt = new StringBuilder();
        for (HarnessPromptPart part : parts) {
            if (prompt.length() > 0) {
                prompt.append("\n\n");
            }
            prompt.append("## ").append(part.getType().name()).append('\n').append(part.getContent());
        }
        String content = prompt.toString();
        return new HarnessPromptAssembly(parts, content, HarnessHashing.sha256(content));
    }

    private void addPackPart(List<HarnessPromptPart> parts, PromptPartType type,
                             PromptPack pack, PromptResourceRole role) {
        PromptPackResource resource = pack.resource(role);
        parts.add(new HarnessPromptPart(type,
                pack.getManifest().getId() + "@" + pack.getManifest().getVersion() + ":" + resource.getPath(),
                resource.getContent(), resource.getSha256()));
    }

    private String selectedSkillInstructions(SkillSelection selection) {
        if (selection.getSelected().isEmpty()) {
            return "No Skill selected.";
        }
        StringBuilder content = new StringBuilder();
        for (SelectedSkill selected : selection.getSelected()) {
            SkillPackage skillPackage = selected.getSkillPackage();
            SkillManifest manifest = skillPackage.getManifest();
            if (content.length() > 0) {
                content.append("\n\n");
            }
            content.append("### ").append(manifest.getId()).append('@').append(manifest.getVersion())
                    .append(" [").append(selected.getReason()).append("]\n")
                    .append("Package-Hash: ").append(skillPackage.getPackageHash()).append('\n')
                    .append(skillPackage.getEntryContent());
        }
        return content.toString();
    }
}
