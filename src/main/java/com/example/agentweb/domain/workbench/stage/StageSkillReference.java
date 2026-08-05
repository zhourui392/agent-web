package com.example.agentweb.domain.workbench.stage;

import com.example.agentweb.domain.shared.DomainText;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * Published Stage 引用的不可变 Skill Artifact。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
@EqualsAndHashCode
public final class StageSkillReference {

    private final String identifier;
    private final String version;
    private final String packageHash;
    private final boolean required;

    public StageSkillReference(
            String identifier, String version, String packageHash, boolean required) {
        this.identifier = DomainText.require(identifier, "Stage Skill identifier", 128);
        this.version = DomainText.require(version, "Stage Skill version", 80);
        this.packageHash = DomainText.requireSha256(packageHash, "Stage Skill package hash");
        this.required = required;
    }
}
