package com.example.agentweb.domain.harness;

import lombok.Getter;

/**
 * CLI Runtime 实际完成的一次命令执行观测，不保存可能含敏感信息的原始输出。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public final class RuntimeCommandObservation {

    private final int sequence;
    private final String command;
    private final int exitCode;
    private final String outputHash;

    public RuntimeCommandObservation(int sequence, String command, int exitCode,
                                     String outputHash) {
        if (sequence < 1) {
            throw new IllegalArgumentException("runtime command sequence must be positive");
        }
        this.sequence = sequence;
        this.command = DomainText.require(command, "runtime observed command", 16384);
        this.exitCode = exitCode;
        this.outputHash = DomainText.requireSha256(outputHash, "runtime command output hash");
    }
}
