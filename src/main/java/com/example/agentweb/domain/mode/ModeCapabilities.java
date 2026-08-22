package com.example.agentweb.domain.mode;

import com.example.agentweb.domain.shared.DomainText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 一个模式选中的能力清单（命令 / Skill / MCP Server 引用）。
 *
 * <p>引用以 {@code (identifier, version, hash)} 三元组冻结，内容仍在不可变
 * Artifact Registry 中按 hash 精确重验；本值对象只负责引用级不变量：
 * 每类内 identifier 唯一、排序位非负、数量有界。</p>
 *
 * @author alex
 * @since 2026-08-20
 */
public final class ModeCapabilities {

    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("[a-z0-9][a-z0-9._:-]{0,127}");
    private static final int MAX_COMMANDS = 32;
    private static final int MAX_SKILLS = 32;
    private static final int MAX_MCP_SERVERS = 16;

    private final List<CommandRef> commands;
    private final List<SkillRef> skills;
    private final List<McpServerRef> mcpServers;

    public ModeCapabilities(List<CommandRef> commands,
                            List<SkillRef> skills,
                            List<McpServerRef> mcpServers) {
        this.commands = sorted(commands, MAX_COMMANDS, "command");
        this.skills = sorted(skills, MAX_SKILLS, "skill");
        this.mcpServers = sorted(mcpServers, MAX_MCP_SERVERS, "MCP server");
    }

    public static ModeCapabilities empty() {
        return new ModeCapabilities(Collections.<CommandRef>emptyList(),
                Collections.<SkillRef>emptyList(),
                Collections.<McpServerRef>emptyList());
    }

    public List<CommandRef> getCommands() {
        return commands;
    }

    public List<SkillRef> getSkills() {
        return skills;
    }

    public List<McpServerRef> getMcpServers() {
        return mcpServers;
    }

    public boolean isEmpty() {
        return commands.isEmpty() && skills.isEmpty() && mcpServers.isEmpty();
    }

    private static <T extends CapabilityRef> List<T> sorted(
            List<T> refs, int bound, String name) {
        if (refs == null) {
            throw new IllegalArgumentException(
                    "mode " + name + " references must be complete");
        }
        for (T ref : refs) {
            if (ref == null) {
                throw new IllegalArgumentException(
                        "mode " + name + " references must be complete");
            }
        }
        if (refs.size() > bound) {
            throw new IllegalArgumentException(
                    "mode " + name + " references exceed the bound of " + bound);
        }
        Map<String, T> unique = new LinkedHashMap<String, T>();
        for (T ref : refs) {
            if (unique.put(ref.getIdentifier(), ref) != null) {
                throw new IllegalArgumentException(
                        "mode " + name + " reference is duplicated: "
                                + ref.getIdentifier());
            }
        }
        List<T> copy = new ArrayList<T>(refs);
        copy.sort(Comparator.comparingInt(CapabilityRef::getSortOrder));
        return Collections.unmodifiableList(copy);
    }

    /**
     * 能力引用的公共不变量。
     */
    public abstract static class CapabilityRef {

        private final String identifier;
        private final String version;
        private final String contentHash;
        private final int sortOrder;

        protected CapabilityRef(String identifier, String version,
                                String contentHash, int sortOrder) {
            this.identifier = requireIdentifier(identifier);
            this.version = DomainText.require(version, "capability version", 80);
            this.contentHash = DomainText.requireSha256(
                    contentHash, "capability content hash");
            if (sortOrder < 0) {
                throw new IllegalArgumentException("capability sort order must not be negative");
            }
            this.sortOrder = sortOrder;
        }

        private static String requireIdentifier(String value) {
            String normalized = DomainText.require(value, "capability identifier", 128);
            if (!IDENTIFIER_PATTERN.matcher(normalized).matches()) {
                throw new IllegalArgumentException(
                        "capability identifier must match [a-z0-9][a-z0-9._:-]{0,127}: " + value);
            }
            return normalized;
        }

        public String getIdentifier() {
            return identifier;
        }

        public String getVersion() {
            return version;
        }

        public String getContentHash() {
            return contentHash;
        }

        public int getSortOrder() {
            return sortOrder;
        }
    }

    /**
     * 选中的斜杠命令（内容为 markdown prompt template）。
     */
    public static final class CommandRef extends CapabilityRef {

        @com.fasterxml.jackson.annotation.JsonCreator
        public CommandRef(
                @com.fasterxml.jackson.annotation.JsonProperty("identifier") String identifier,
                @com.fasterxml.jackson.annotation.JsonProperty("version") String version,
                @com.fasterxml.jackson.annotation.JsonProperty("contentHash") String contentHash,
                @com.fasterxml.jackson.annotation.JsonProperty("sortOrder") int sortOrder) {
            super(identifier, version, contentHash, sortOrder);
        }
    }

    /**
     * 选中的 Skill 包。
     */
    public static final class SkillRef extends CapabilityRef {

        @com.fasterxml.jackson.annotation.JsonCreator
        public SkillRef(
                @com.fasterxml.jackson.annotation.JsonProperty("identifier") String identifier,
                @com.fasterxml.jackson.annotation.JsonProperty("version") String version,
                @com.fasterxml.jackson.annotation.JsonProperty("contentHash") String contentHash,
                @com.fasterxml.jackson.annotation.JsonProperty("sortOrder") int sortOrder) {
            super(identifier, version, contentHash, sortOrder);
        }
    }

    /**
     * 选中的 MCP Server，额外冻结访问级别与 transport，
     * 供运行时对 Registry 定义做 exact 重验。
     */
    public static final class McpServerRef extends CapabilityRef {

        private final String maximumAccess;
        private final String transport;

        @com.fasterxml.jackson.annotation.JsonCreator
        public McpServerRef(
                @com.fasterxml.jackson.annotation.JsonProperty("identifier") String identifier,
                @com.fasterxml.jackson.annotation.JsonProperty("version") String version,
                @com.fasterxml.jackson.annotation.JsonProperty("contentHash") String contentHash,
                @com.fasterxml.jackson.annotation.JsonProperty("maximumAccess") String maximumAccess,
                @com.fasterxml.jackson.annotation.JsonProperty("transport") String transport,
                @com.fasterxml.jackson.annotation.JsonProperty("sortOrder") int sortOrder) {
            super(identifier, version, contentHash, sortOrder);
            if (maximumAccess == null
                    || !(maximumAccess.equals("READ") || maximumAccess.equals("WRITE"))) {
                throw new IllegalArgumentException(
                        "MCP maximum access must be READ or WRITE: " + maximumAccess);
            }
            this.maximumAccess = maximumAccess;
            this.transport = DomainText.require(transport, "MCP transport", 120);
        }

        public String getMaximumAccess() {
            return maximumAccess;
        }

        public String getTransport() {
            return transport;
        }
    }
}
