package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 已由 Catalog 完整读取并计算 Package Hash 的不可变 Skill 包。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class SkillPackage {

    private final SkillManifest manifest;
    private final String packageHash;
    private final String entryContent;
    private final Map<String, String> resourceHashes;

    public SkillPackage(SkillManifest manifest, String packageHash, String entryContent,
                        Map<String, String> resourceHashes) {
        if (manifest == null) {
            throw new IllegalArgumentException("skill manifest must not be null");
        }
        this.manifest = manifest;
        this.packageHash = DomainText.requireSha256(packageHash, "skill package hash");
        if (entryContent == null || entryContent.trim().isEmpty()) {
            throw new IllegalArgumentException("skill entry content must not be blank");
        }
        this.entryContent = entryContent;
        if (resourceHashes == null) {
            throw new IllegalArgumentException("skill resource hashes must not be null");
        }
        Map<String, String> hashes = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : resourceHashes.entrySet()) {
            hashes.put(DomainText.require(entry.getKey(), "skill resource path", 500),
                    DomainText.requireSha256(entry.getValue(), "skill resource hash"));
        }
        this.resourceHashes = Collections.unmodifiableMap(hashes);
    }
}
