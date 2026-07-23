package com.example.agentweb.app.harness;

import lombok.Getter;

/**
 * 创建 Harness Run 的应用命令。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class CreateHarnessRunCommand {

    private final String title;
    private final String workingDir;
    private final String agentType;
    private final String environment;
    private final String definitionVersion;
    private final String idempotencyKey;

    public CreateHarnessRunCommand(String title, String workingDir, String agentType,
                                   String environment, String definitionVersion,
                                   String idempotencyKey) {
        this.title = title;
        this.workingDir = workingDir;
        this.agentType = agentType;
        this.environment = environment;
        this.definitionVersion = definitionVersion;
        this.idempotencyKey = idempotencyKey;
    }
}
