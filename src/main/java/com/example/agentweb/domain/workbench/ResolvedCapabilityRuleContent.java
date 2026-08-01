package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.capability.ResolvedRuleBinding;
import com.example.agentweb.domain.shared.CanonicalHashing;
import lombok.Getter;

/**
 * 仅供私有 Run Prompt 使用的 exact Rule 正文；公开 Binding 仍只携带 Hash 与安全摘要。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class ResolvedCapabilityRuleContent {

    private final String id;
    private final String version;
    private final String source;
    private final String content;
    private final String contentHash;

    private ResolvedCapabilityRuleContent(
            ResolvedRuleBinding binding, String content) {
        if (binding == null || content == null
                || content.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "resolved capability rule binding and content are required");
        }
        this.id = binding.getId();
        this.version = binding.getVersion();
        this.source = binding.getSource();
        this.content = content;
        this.contentHash = CanonicalHashing.sha256(content);
        if (!binding.getContentHash().equals(contentHash)) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
    }

    public static ResolvedCapabilityRuleContent bind(
            ResolvedRuleBinding binding, String content) {
        return new ResolvedCapabilityRuleContent(binding, content);
    }

    public void requireExactBinding(ResolvedRuleBinding binding) {
        if (binding == null
                || !id.equals(binding.getId())
                || !version.equals(binding.getVersion())
                || !source.equals(binding.getSource())
                || !contentHash.equals(binding.getContentHash())) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
    }
}
