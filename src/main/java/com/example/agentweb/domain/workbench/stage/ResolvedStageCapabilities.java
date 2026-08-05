package com.example.agentweb.domain.workbench.stage;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stage 发布前从当前 Catalog 解析并归档完成的精确能力引用。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class ResolvedStageCapabilities {

    private final List<StageCommandReference> commands;
    private final List<StageSkillReference> skills;
    private final List<StageMcpServerReference> mcpServers;

    public ResolvedStageCapabilities(
            List<StageCommandReference> commands,
            List<StageSkillReference> skills,
            List<StageMcpServerReference> mcpServers) {
        this.commands = immutable(commands, StageCommandReference::getIdentifier,
                "resolved Stage Commands");
        this.skills = immutable(skills, StageSkillReference::getIdentifier,
                "resolved Stage Skills");
        this.mcpServers = immutable(mcpServers, StageMcpServerReference::getIdentifier,
                "resolved Stage MCP Servers");
    }

    private <T> List<T> immutable(
            List<T> source, java.util.function.Function<T, String> identifier,
            String name) {
        if (source == null) {
            throw new IllegalArgumentException(name + " must not contain null");
        }
        List<T> copy = new ArrayList<T>(source);
        for (T value : copy) {
            if (value == null) {
                throw new IllegalArgumentException(name + " must not contain null");
            }
        }
        copy.sort(Comparator.comparing(identifier));
        Set<String> identifiers = new HashSet<String>();
        for (T value : copy) {
            if (!identifiers.add(identifier.apply(value))) {
                throw new IllegalArgumentException(name + " must use unique identifiers");
            }
        }
        return Collections.unmodifiableList(copy);
    }
}
