package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Prompt Pack 的版本、阶段和必需资源声明。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class PromptPackManifest {

    private final String id;
    private final String version;
    private final HarnessStage stage;
    private final Map<PromptResourceRole, String> resourcePaths;

    public PromptPackManifest(String id, String version, HarnessStage stage,
                              Map<PromptResourceRole, String> resourcePaths) {
        this.id = DomainText.require(id, "prompt pack id", 120);
        this.version = DomainText.require(version, "prompt pack version", 60);
        if (stage == null) {
            throw new IllegalArgumentException("prompt pack stage must not be null");
        }
        this.stage = stage;
        if (resourcePaths == null || resourcePaths.size() != PromptResourceRole.values().length
                || !resourcePaths.keySet().containsAll(java.util.Arrays.asList(PromptResourceRole.values()))) {
            throw new IllegalArgumentException("prompt pack manifest must declare exactly all required resources");
        }
        Map<PromptResourceRole, String> copy = new EnumMap<PromptResourceRole, String>(PromptResourceRole.class);
        for (Map.Entry<PromptResourceRole, String> entry : resourcePaths.entrySet()) {
            copy.put(entry.getKey(), DomainText.require(entry.getValue(), "prompt resource path", 500));
        }
        this.resourcePaths = Collections.unmodifiableMap(copy);
    }
}
