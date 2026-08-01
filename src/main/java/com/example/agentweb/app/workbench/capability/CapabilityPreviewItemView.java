package com.example.agentweb.app.workbench.capability;

import com.example.agentweb.domain.workbench.PhaseCapabilityPreviewItem;
import lombok.Getter;

/**
 * Capability Drawer 单项安全 DTO。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class CapabilityPreviewItemView {

    private final String id;
    private final boolean required;
    private final boolean selected;
    private final String source;
    private final String summary;

    CapabilityPreviewItemView(PhaseCapabilityPreviewItem item) {
        this.id = item.getId();
        this.required = item.isRequired();
        this.selected = item.isSelected();
        this.source = item.getSource();
        this.summary = item.getSummary();
    }
}
