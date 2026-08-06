package com.example.agentweb.app.workbench.stage;

import com.example.agentweb.domain.capability.CommandDefinition;
import com.example.agentweb.domain.capability.SkillManifest;
import com.example.agentweb.domain.capability.SkillPackage;
import lombok.Getter;

/**
 * Workbench Stage 冻结 Slash Command 的安全查询投影。
 *
 * <p>同时覆盖 Command 和 Skill 两种冻结引用：Command 来自
 * {@link CommandDefinition}，Skill 来自 {@link SkillPackage}。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class WorkbenchStageCommandView {

    private final String identifier;
    private final String version;
    private final String displayName;
    private final String description;
    private final String argumentHint;

    private WorkbenchStageCommandView(CommandDefinition command) {
        this.identifier = command.getIdentifier();
        this.version = command.getVersion();
        this.displayName = command.getDisplayName();
        this.description = command.getDescription();
        this.argumentHint = command.getArgumentHint();
    }

    private WorkbenchStageCommandView(
            String identifier, String version, String displayName,
            String description, String argumentHint) {
        this.identifier = identifier;
        this.version = version;
        this.displayName = displayName;
        this.description = description;
        this.argumentHint = argumentHint;
    }

    static WorkbenchStageCommandView from(CommandDefinition command) {
        if (command == null) {
            throw new IllegalArgumentException(
                    "Stage Command definition is required");
        }
        return new WorkbenchStageCommandView(command);
    }

    static WorkbenchStageCommandView fromSkill(SkillPackage skill) {
        if (skill == null) {
            throw new IllegalArgumentException(
                    "Stage Skill package is required");
        }
        SkillManifest manifest = skill.getManifest();
        return new WorkbenchStageCommandView(
                manifest.getId(), manifest.getVersion(),
                manifest.getId(), manifest.getDescription(), null);
    }
}
