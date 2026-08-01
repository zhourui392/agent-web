package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

/**
 * 一项不含路径、Secret 或 Catalog 内部定义的能力预览。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PhaseCapabilityPreviewItem {

    private final String id;
    private final boolean required;
    private final boolean selected;
    private final String source;
    private final String summary;
    private final CapabilityAccess access;

    PhaseCapabilityPreviewItem(
            PhaseCapabilityType type, String id,
            boolean required, boolean selected,
            String source, String summary, CapabilityAccess access) {
        if (type == null) {
            throw new IllegalArgumentException(
                    "capability preview type must not be null");
        }
        this.id = DomainText.require(id, "capability preview id", 160);
        this.required = required;
        if (required && !selected) {
            throw new IllegalArgumentException(
                    "required capability preview must be selected");
        }
        this.selected = selected;
        this.source = DomainText.require(
                source, "capability preview source", 160);
        this.summary = summary == null ? null
                : DomainText.require(summary, "capability preview summary", 1000);
        if (type != PhaseCapabilityType.MCP_SERVER && access != null) {
            throw new IllegalArgumentException(
                    "only MCP capability preview may expose access");
        }
        this.access = access;
    }
}
