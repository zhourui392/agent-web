package com.example.agentweb.domain.harness;

import lombok.Getter;

/**
 * Codex 可自动发现的一项工作区 Repo Skill 技术事实。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class WorkspaceRepoSkill {

    private final String id;
    private final String relativeEntryPath;
    private final String entryHash;

    public WorkspaceRepoSkill(String id, String relativeEntryPath, String entryHash) {
        this.id = DomainText.require(id, "workspace repo skill id", 120);
        this.relativeEntryPath = DomainText.require(
                relativeEntryPath, "workspace repo skill entry path", 500);
        this.entryHash = DomainText.requireSha256(entryHash, "workspace repo skill entry hash");
    }
}
