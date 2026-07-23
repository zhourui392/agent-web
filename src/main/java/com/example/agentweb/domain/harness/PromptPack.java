package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 一次解析得到的不可变 Prompt Pack。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class PromptPack {

    private final PromptPackManifest manifest;
    private final List<PromptPackResource> resources;
    private final String packageHash;
    private final Map<PromptResourceRole, PromptPackResource> resourcesByRole;

    public PromptPack(PromptPackManifest manifest, List<PromptPackResource> resources, String packageHash) {
        if (manifest == null || resources == null || resources.contains(null)) {
            throw new IllegalArgumentException("prompt pack manifest and resources must not be null");
        }
        this.manifest = manifest;
        this.packageHash = DomainText.requireSha256(packageHash, "prompt pack hash");
        Map<PromptResourceRole, PromptPackResource> indexed = new EnumMap<PromptResourceRole, PromptPackResource>(
                PromptResourceRole.class);
        for (PromptPackResource resource : resources) {
            if (indexed.put(resource.getRole(), resource) != null) {
                throw new IllegalArgumentException("prompt pack contains duplicate resource role: "
                        + resource.getRole());
            }
            String declaredPath = manifest.getResourcePaths().get(resource.getRole());
            if (!resource.getPath().equals(declaredPath)) {
                throw new IllegalArgumentException("prompt resource path differs from manifest: "
                        + resource.getRole());
            }
        }
        if (indexed.size() != PromptResourceRole.values().length) {
            throw new IllegalArgumentException("prompt pack must contain exactly all required resources");
        }
        this.resources = Collections.unmodifiableList(new ArrayList<PromptPackResource>(resources));
        this.resourcesByRole = Collections.unmodifiableMap(indexed);
    }

    public PromptPackResource resource(PromptResourceRole role) {
        PromptPackResource resource = resourcesByRole.get(role);
        if (resource == null) {
            throw new IllegalStateException("required prompt resource is missing: " + role);
        }
        return resource;
    }

    public Map<String, String> resourceHashes() {
        Map<String, String> hashes = new java.util.TreeMap<String, String>();
        for (PromptPackResource resource : resources) {
            hashes.put(resource.getPath(), resource.getSha256());
        }
        return Collections.unmodifiableMap(hashes);
    }
}
