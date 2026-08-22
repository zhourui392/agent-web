package com.example.agentweb.domain.mode;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 用户自定义模式聚合根。
 *
 * <p>模式 = 一组可被会话绑定的能力配置 + 展示元信息。identifier 创建后不可变
 * （作为稳定引用）；其余字段经 {@link #edit} 整体校验后覆盖更新。进行中会话
 * 读取的是创建时冻结的 {@link ModeSnapshot}，不受编辑影响。</p>
 *
 * @author alex
 * @since 2026-08-20
 */
@Getter
public class ChatMode {

    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final java.util.Set<String> EFFORT_VALUES =
            java.util.Set.of("low", "medium", "high");

    private final String id;
    private final String userId;
    private final String identifier;
    private final Long sourceRevisionId;
    private final Instant createdAt;
    private String displayName;
    private String description;
    private String defaultPrompt;
    private ModePermissionMode permissionMode;
    private String model;
    private String effort;
    private ModeCapabilities capabilities;
    private Instant updatedAt;
    private int version;

    private ChatMode(String id, String userId, String identifier,
                     Long sourceRevisionId, Instant createdAt) {
        this.id = DomainText.require(id, "mode id", 128);
        this.userId = DomainText.require(userId, "mode owner id", 128);
        if (!IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                    "mode identifier must match [a-z0-9][a-z0-9._-]{0,63}: " + identifier);
        }
        this.identifier = identifier;
        this.sourceRevisionId = sourceRevisionId;
        this.createdAt = DomainText.requireTime(createdAt, "mode created at");
    }

    public static ChatMode create(String id, String userId, String identifier,
                                  String displayName, String description,
                                  String defaultPrompt, ModePermissionMode permissionMode,
                                  String model, String effort, Long sourceRevisionId,
                                  ModeCapabilities capabilities, Instant now) {
        ChatMode mode = new ChatMode(id, userId, identifier, sourceRevisionId, now);
        mode.apply(displayName, description, defaultPrompt, permissionMode,
                model, effort, capabilities, now);
        mode.version = 0;
        return mode;
    }

    /** 仓储装配专用：按持久化事实恢复聚合，不触发 edit 的版本自增。 */
    public static ChatMode restore(String id, String userId, String identifier,
                                   String displayName, String description,
                                   String defaultPrompt, ModePermissionMode permissionMode,
                                   String model, String effort, Long sourceRevisionId,
                                   ModeCapabilities capabilities,
                                   Instant createdAt, Instant updatedAt, int version) {
        ChatMode mode = new ChatMode(id, userId, identifier, sourceRevisionId, createdAt);
        mode.apply(displayName, description, defaultPrompt, permissionMode,
                model, effort, capabilities, updatedAt);
        mode.version = version;
        return mode;
    }

    /**
     * 编辑覆盖：整体校验后一次性应用，版本号自增。
     * identifier 与归属不可编辑；capabilities 为 {@code null} 表示保持不变。
     */
    public void edit(String displayName, String description, String defaultPrompt,
                     ModePermissionMode permissionMode, String model, String effort,
                     ModeCapabilities capabilities, Instant now) {
        apply(displayName, description, defaultPrompt, permissionMode,
                model, effort,
                capabilities == null ? this.capabilities : capabilities, now);
        this.version++;
    }

    private void apply(String displayName, String description, String defaultPrompt,
                       ModePermissionMode permissionMode, String model, String effort,
                       ModeCapabilities capabilities, Instant now) {
        if (displayName == null || displayName.trim().isEmpty()) {
            throw new IllegalArgumentException("mode display name must not be blank");
        }
        this.displayName = DomainText.require(displayName, "mode display name", 128);
        this.description = optional(description, 2000);
        this.defaultPrompt = optional(defaultPrompt, 16384);
        this.permissionMode = permissionMode;
        this.model = optional(model, 128);
        this.effort = requireEffort(effort);
        this.capabilities = Objects.requireNonNull(
                capabilities == null ? ModeCapabilities.empty() : capabilities,
                "capabilities");
        this.updatedAt = DomainText.requireTime(now, "mode updated at");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("mode updated at must not be before created at");
        }
    }

    /**
     * 归属校验：仅属主可编辑/删除/绑定自己的模式；越权统一按不存在语义拒绝。
     */
    public void requireOwnedBy(String currentUserId) {
        if (!userId.equals(currentUserId)) {
            throw new ModeNotFoundException(id);
        }
    }

    /**
     * 冻结当前模式为会话可持久化的能力快照。
     */
    public ModeSnapshot snapshot() {
        return ModeSnapshot.from(this);
    }

    private static String optional(String value, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return DomainText.require(value, "mode field", maxLength);
    }

    private static String requireEffort(String effort) {
        if (effort == null || effort.trim().isEmpty()) {
            return null;
        }
        String normalized = effort.trim();
        if (!EFFORT_VALUES.contains(normalized)) {
            throw new IllegalArgumentException(
                    "mode effort must be one of low/medium/high: " + effort);
        }
        return normalized;
    }
}
