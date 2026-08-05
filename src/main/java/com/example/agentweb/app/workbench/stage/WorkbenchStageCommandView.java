package com.example.agentweb.app.workbench.stage;

import com.example.agentweb.domain.capability.CommandDefinition;
import lombok.Getter;

/**
 * Workbench Stage 冻结 Slash Command 的安全查询投影。
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

    static WorkbenchStageCommandView from(CommandDefinition command) {
        if (command == null) {
            throw new IllegalArgumentException(
                    "Stage Command definition is required");
        }
        return new WorkbenchStageCommandView(command);
    }
}
