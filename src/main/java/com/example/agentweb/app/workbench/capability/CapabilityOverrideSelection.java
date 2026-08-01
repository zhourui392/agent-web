package com.example.agentweb.app.workbench.capability;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 保留调用方原始 ID 次序与重复项的 Override 选择输入。
 *
 * <p>重复、互斥、required/optional 分类与 Catalog 信任由 Resolver 和 Domain 校验，
 * 本类型不提前转 Set 或静默去重。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class CapabilityOverrideSelection {

    private final List<String> addedOptionalSkillIds;
    private final List<String> removedOptionalSkillIds;
    private final List<String> selectedOptionalMcpIds;
    private final List<String> selectedOptionalRuleIds;
    private final String additionalRule;

    public CapabilityOverrideSelection(
            List<String> addedOptionalSkillIds,
            List<String> removedOptionalSkillIds,
            List<String> selectedOptionalMcpIds,
            List<String> selectedOptionalRuleIds) {
        this(addedOptionalSkillIds, removedOptionalSkillIds,
                selectedOptionalMcpIds, selectedOptionalRuleIds, null);
    }

    public CapabilityOverrideSelection(
            List<String> addedOptionalSkillIds,
            List<String> removedOptionalSkillIds,
            List<String> selectedOptionalMcpIds,
            List<String> selectedOptionalRuleIds,
            String additionalRule) {
        this.addedOptionalSkillIds = immutableCopy(
                addedOptionalSkillIds, "addedOptionalSkillIds");
        this.removedOptionalSkillIds = immutableCopy(
                removedOptionalSkillIds, "removedOptionalSkillIds");
        this.selectedOptionalMcpIds = immutableCopy(
                selectedOptionalMcpIds, "selectedOptionalMcpIds");
        this.selectedOptionalRuleIds = immutableCopy(
                selectedOptionalRuleIds, "selectedOptionalRuleIds");
        this.additionalRule = additionalRule;
    }

    private static List<String> immutableCopy(
            List<String> values, String name) {
        Objects.requireNonNull(values, name);
        return Collections.unmodifiableList(new ArrayList<String>(values));
    }
}
