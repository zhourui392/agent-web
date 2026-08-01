package com.example.agentweb.domain.capability;

import com.example.agentweb.domain.shared.DomainText;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * 一项已解析且可审计的 Skill 绑定。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@EqualsAndHashCode
public final class ResolvedSkillBinding {

    private final String id;
    private final String version;
    private final String source;
    private final String packageHash;
    private final String trustTier;

    public ResolvedSkillBinding(String id, String version, String source, String packageHash,
                                String trustTier) {
        this.id = DomainText.require(id, "skill id", 160);
        this.version = DomainText.require(version, "skill version", 80);
        this.source = DomainText.require(source, "skill source", 120);
        this.packageHash = DomainText.requireSha256(packageHash, "skill package hash");
        this.trustTier = DomainText.require(trustTier, "skill trust tier", 120);
    }
}
