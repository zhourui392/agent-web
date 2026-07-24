package com.example.agentweb.domain.harness;

import lombok.Getter;

/**
 * 管理员注册部署命令模板的不可变身份。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public final class DeploymentTemplateReference {

    private final String templateId;
    private final String version;
    private final String templateHash;
    private final boolean rollbackConfigured;

    public DeploymentTemplateReference(String templateId, String version,
                                       String templateHash, boolean rollbackConfigured) {
        this.templateId = DomainText.require(templateId, "deployment template id", 128);
        this.version = DomainText.require(version, "deployment template version", 64);
        this.templateHash = DomainText.requireSha256(templateHash, "deployment template hash");
        if (!rollbackConfigured) {
            throw new IllegalArgumentException("local deployment template must configure rollback");
        }
        this.rollbackConfigured = true;
    }

    public boolean sameIdentity(DeploymentTemplateReference other) {
        return other != null && templateId.equals(other.templateId)
                && version.equals(other.version) && templateHash.equals(other.templateHash)
                && rollbackConfigured == other.rollbackConfigured;
    }
}
