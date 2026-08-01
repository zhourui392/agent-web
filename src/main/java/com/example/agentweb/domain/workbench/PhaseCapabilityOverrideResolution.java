package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 已持久化阶段能力覆盖相对当前 Profile 的安全降级结果。
 *
 * <p>仅输出当前 Profile 仍允许的有效覆盖；失效事实与可展示告警独立保留，
 * 禁止用未知能力替换当前默认能力。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PhaseCapabilityOverrideResolution {

    public static final String RESTORED_DEFAULT_WARNING =
            "高级覆盖基线已失效，已恢复默认配置";
    public static final String PARTIAL_RESTORED_DEFAULT_WARNING =
            "部分高级覆盖项已失效，已恢复对应默认配置";

    public enum OverrideField {
        BASE_PROFILE,
        ADDED_OPTIONAL_SKILL,
        REMOVED_OPTIONAL_SKILL,
        SELECTED_OPTIONAL_MCP,
        SELECTED_OPTIONAL_RULE
    }

    private final CapabilityOverride effectiveOverride;
    private final List<IgnoredItem> ignoredItems;
    private final List<String> warnings;

    private PhaseCapabilityOverrideResolution(
            CapabilityOverride effectiveOverride,
            List<IgnoredItem> ignoredItems,
            List<String> warnings) {
        if (effectiveOverride == null || ignoredItems == null
                || ignoredItems.contains(null) || warnings == null
                || warnings.contains(null)) {
            throw new IllegalArgumentException(
                    "capability override resolution facts must be complete");
        }
        this.effectiveOverride = effectiveOverride;
        this.ignoredItems = Collections.unmodifiableList(
                new ArrayList<IgnoredItem>(ignoredItems));
        this.warnings = Collections.unmodifiableList(
                new ArrayList<String>(warnings));
    }

    static PhaseCapabilityOverrideResolution accepted(
            CapabilityOverride effectiveOverride) {
        return new PhaseCapabilityOverrideResolution(
                effectiveOverride, Collections.<IgnoredItem>emptyList(),
                Collections.<String>emptyList());
    }

    static PhaseCapabilityOverrideResolution restoredDefault(
            String baseProfileId, String baseProfileVersion) {
        IgnoredItem ignored = IgnoredItem.of(
                OverrideField.BASE_PROFILE,
                DomainText.require(baseProfileId,
                        "capability base profile id", 160)
                        + "@" + DomainText.require(baseProfileVersion,
                        "capability base profile version", 80),
                "BASE_PROFILE_CHANGED");
        return new PhaseCapabilityOverrideResolution(
                CapabilityOverride.empty(), Collections.singletonList(ignored),
                Collections.singletonList(RESTORED_DEFAULT_WARNING));
    }

    static PhaseCapabilityOverrideResolution filtered(
            CapabilityOverride effectiveOverride,
            List<IgnoredItem> ignoredItems) {
        if (ignoredItems == null || ignoredItems.isEmpty()) {
            return accepted(effectiveOverride);
        }
        return new PhaseCapabilityOverrideResolution(
                effectiveOverride, ignoredItems,
                Collections.singletonList(
                        PARTIAL_RESTORED_DEFAULT_WARNING));
    }

    static IgnoredItem ignored(
            OverrideField field, String capabilityId) {
        return IgnoredItem.of(
                field, capabilityId, "NOT_OPTIONAL_IN_CURRENT_PROFILE");
    }

    /**
     * 一个被忽略的旧覆盖项；只保存安全 ID 和稳定原因码，不保存 Rule 正文。
     *
     * @author alex
     * @since 2026-08-01
     */
    @Getter
    public static final class IgnoredItem {

        private final OverrideField field;
        private final String capabilityId;
        private final String reasonCode;

        private IgnoredItem(
                OverrideField field, String capabilityId,
                String reasonCode) {
            if (field == null) {
                throw new IllegalArgumentException(
                        "ignored capability override field must not be null");
            }
            this.field = field;
            this.capabilityId = DomainText.require(
                    capabilityId, "ignored capability override id", 256);
            this.reasonCode = DomainText.require(
                    reasonCode, "ignored capability override reason", 160);
        }

        private static IgnoredItem of(
                OverrideField field, String capabilityId,
                String reasonCode) {
            return new IgnoredItem(field, capabilityId, reasonCode);
        }
    }
}
