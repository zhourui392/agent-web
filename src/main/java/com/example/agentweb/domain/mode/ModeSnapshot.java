package com.example.agentweb.domain.mode;

import com.example.agentweb.domain.shared.CanonicalHashing;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 会话创建时冻结的模式能力快照。
 *
 * <p>序列化为 JSON 存入 {@code chat_session.mode_snapshot}；模式后续编辑不影响
 * 已冻结快照——"会话内能力稳定"不变量由本对象的不可变性表达（无任何变更方法）。</p>
 *
 * @author alex
 * @since 2026-08-20
 */
public final class ModeSnapshot {

    private final String sourceModeId;
    private final String identifier;
    private final String displayName;
    private final String description;
    private final String defaultPrompt;
    private final String permissionMode;
    private final String model;
    private final String effort;
    private final List<ModeCapabilities.CommandRef> commands;
    private final List<ModeCapabilities.SkillRef> skills;
    private final List<ModeCapabilities.McpServerRef> mcpServers;

    @JsonCreator
    public ModeSnapshot(
            @JsonProperty("sourceModeId") String sourceModeId,
            @JsonProperty("identifier") String identifier,
            @JsonProperty("displayName") String displayName,
            @JsonProperty("description") String description,
            @JsonProperty("defaultPrompt") String defaultPrompt,
            @JsonProperty("permissionMode") String permissionMode,
            @JsonProperty("model") String model,
            @JsonProperty("effort") String effort,
            @JsonProperty("commands") List<ModeCapabilities.CommandRef> commands,
            @JsonProperty("skills") List<ModeCapabilities.SkillRef> skills,
            @JsonProperty("mcpServers") List<ModeCapabilities.McpServerRef> mcpServers) {
        this.sourceModeId = Objects.requireNonNull(sourceModeId, "sourceModeId");
        this.identifier = Objects.requireNonNull(identifier, "identifier");
        this.displayName = displayName;
        this.description = description;
        this.defaultPrompt = defaultPrompt;
        // 构造期即校验合法值, 防止持久化 JSON 被写坏后运行时才爆
        this.permissionMode = permissionMode == null
                ? null : ModePermissionMode.fromCliValue(permissionMode).cliValue();
        this.model = model;
        this.effort = effort;
        this.commands = immutable(commands);
        this.skills = immutable(skills);
        this.mcpServers = immutable(mcpServers);
    }

    static ModeSnapshot from(ChatMode mode) {
        ModeCapabilities capabilities = mode.getCapabilities();
        return new ModeSnapshot(mode.getId(), mode.getIdentifier(),
                mode.getDisplayName(), mode.getDescription(),
                mode.getDefaultPrompt(),
                mode.getPermissionMode() == null
                        ? null : mode.getPermissionMode().cliValue(),
                mode.getModel(), mode.getEffort(),
                capabilities.getCommands(), capabilities.getSkills(),
                capabilities.getMcpServers());
    }

    public String getSourceModeId() {
        return sourceModeId;
    }

    public String getIdentifier() {
        return identifier;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public String getDefaultPrompt() {
        return defaultPrompt;
    }

    /** CLI 原始值；{@code null} 表示不传 {@code --permission-mode}。 */
    public String getPermissionMode() {
        return permissionMode;
    }

    public String getModel() {
        return model;
    }

    public String getEffort() {
        return effort;
    }

    public List<ModeCapabilities.CommandRef> getCommands() {
        return commands;
    }

    public List<ModeCapabilities.SkillRef> getSkills() {
        return skills;
    }

    public List<ModeCapabilities.McpServerRef> getMcpServers() {
        return mcpServers;
    }

    /**
     * 冻结快照的规范 hash，用作能力绑定重验的 profile hash。
     */
    public String snapshotHash() {
        StringBuilder canonical = new StringBuilder();
        canonical.append("mode-snapshot@1\n");
        canonical.append(sourceModeId).append('\n')
                .append(identifier).append('\n')
                .append(nullToEmpty(defaultPrompt)).append('\n')
                .append(nullToEmpty(permissionMode)).append('\n')
                .append(nullToEmpty(model)).append('\n')
                .append(nullToEmpty(effort)).append('\n');
        appendRefs(canonical, commands);
        appendRefs(canonical, skills);
        for (ModeCapabilities.McpServerRef ref : mcpServers) {
            canonical.append(ref.getIdentifier()).append('\0')
                    .append(ref.getVersion()).append('\0')
                    .append(ref.getContentHash()).append('\0')
                    .append(ref.getMaximumAccess()).append('\0')
                    .append(ref.getTransport()).append('\0')
                    .append(ref.getSortOrder()).append('\n');
        }
        return CanonicalHashing.sha256(canonical.toString());
    }

    private static void appendRefs(StringBuilder target,
                                   List<? extends ModeCapabilities.CapabilityRef> refs) {
        for (ModeCapabilities.CapabilityRef ref : refs) {
            target.append(ref.getIdentifier()).append('\0')
                    .append(ref.getVersion()).append('\0')
                    .append(ref.getContentHash()).append('\0')
                    .append(ref.getSortOrder()).append('\n');
        }
    }

    private static <T extends ModeCapabilities.CapabilityRef> List<T> immutable(
            List<T> refs) {
        return refs == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(refs));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
