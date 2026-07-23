package com.example.agentweb.domain.harness;

import lombok.Getter;

/**
 * Capability Snapshot 中冻结的 Skill 身份、Hash 与选择原因。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class SnapshotSkill {

    private final String id;
    private final String version;
    private final String packageHash;
    private final SkillSelectionReason reason;

    public SnapshotSkill(String id, String version, String packageHash, SkillSelectionReason reason) {
        this.id = DomainText.require(id, "snapshot skill id", 120);
        this.version = DomainText.require(version, "snapshot skill version", 60);
        this.packageHash = DomainText.requireSha256(packageHash, "snapshot skill package hash");
        if (reason == null) {
            throw new IllegalArgumentException("snapshot skill reason must not be null");
        }
        this.reason = reason;
    }
}
