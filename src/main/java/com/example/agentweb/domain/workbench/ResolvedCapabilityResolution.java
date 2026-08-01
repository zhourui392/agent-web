package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.ResolvedRuleBinding;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 同一次可信 Catalog 解析产生的公开能力 Binding 与私有 Rule 正文。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class ResolvedCapabilityResolution {

    private final ResolvedCapabilityBinding binding;
    private final List<ResolvedCapabilityRuleContent> ruleContents;

    private ResolvedCapabilityResolution(
            ResolvedCapabilityBinding binding,
            List<ResolvedCapabilityRuleContent> ruleContents) {
        if (binding == null || ruleContents == null
                || ruleContents.contains(null)) {
            throw new IllegalArgumentException(
                    "capability binding and rule contents must be complete");
        }
        Map<String, ResolvedCapabilityRuleContent> byId =
                index(ruleContents);
        List<ResolvedCapabilityRuleContent> ordered =
                new ArrayList<ResolvedCapabilityRuleContent>();
        for (ResolvedRuleBinding rule : binding.getRules()) {
            ResolvedCapabilityRuleContent content = byId.remove(
                    rule.getId());
            if (content == null) {
                throw WorkbenchDomainException.runBindingCorrupted();
            }
            content.requireExactBinding(rule);
            ordered.add(content);
        }
        if (!byId.isEmpty()) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
        this.binding = binding;
        this.ruleContents = Collections.unmodifiableList(ordered);
    }

    public static ResolvedCapabilityResolution of(
            ResolvedCapabilityBinding binding,
            List<ResolvedCapabilityRuleContent> ruleContents) {
        return new ResolvedCapabilityResolution(binding, ruleContents);
    }

    private static Map<String, ResolvedCapabilityRuleContent> index(
            List<ResolvedCapabilityRuleContent> contents) {
        Map<String, ResolvedCapabilityRuleContent> indexed =
                new LinkedHashMap<String, ResolvedCapabilityRuleContent>();
        for (ResolvedCapabilityRuleContent content : contents) {
            if (indexed.put(content.getId(), content) != null) {
                throw WorkbenchDomainException.runBindingCorrupted();
            }
        }
        return indexed;
    }
}
