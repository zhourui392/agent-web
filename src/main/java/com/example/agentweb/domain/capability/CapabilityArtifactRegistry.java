package com.example.agentweb.domain.capability;

/**
 * 已发布 Stage 使用的不可变 Capability 内容仓储。
 *
 * @author alex
 * @since 2026-08-05
 */
public interface CapabilityArtifactRegistry {

    void archiveCommand(CommandDefinition definition);

    void archiveSkill(SkillPackage skillPackage);

    void archiveMcpServer(McpServerDefinition definition);

    CommandDefinition requireCommand(
            String identifier, String version, String expectedContentHash);

    SkillPackage requireSkill(
            String identifier, String version, String expectedPackageHash);

    McpServerDefinition requireMcpServer(
            String identifier, String version, String expectedDefinitionHash);
}
