package com.example.agentweb.domain.capability;

import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.shared.DomainText;

import lombok.Getter;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 已由 Catalog 完整读取并计算 Package Hash 的不可变 Skill 包。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public final class SkillPackage {

    private final SkillManifest manifest;
    private final String packageHash;
    private final String entryContent;
    private final Map<String, String> resourceHashes;
    private final Map<String, byte[]> resourceContents;

    public SkillPackage(SkillManifest manifest, String packageHash, String entryContent,
                        Map<String, String> resourceHashes) {
        this(manifest, packageHash, entryContent, resourceHashes,
                Collections.<String, byte[]>emptyMap(), false);
    }

    public SkillPackage(SkillManifest manifest, String packageHash, String entryContent,
                        Map<String, String> resourceHashes,
                        Map<String, byte[]> resourceContents) {
        this(manifest, packageHash, entryContent, resourceHashes,
                resourceContents, true);
    }

    private SkillPackage(SkillManifest manifest, String packageHash, String entryContent,
                         Map<String, String> resourceHashes,
                         Map<String, byte[]> resourceContents,
                         boolean requireCompleteContents) {
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
        if (requireCompleteContents) {
            String entryHash = hashes.get(manifest.getEntryPath());
            if (entryHash == null || !entryHash.equals(CanonicalHashing.sha256(
                    entryContent.getBytes(StandardCharsets.UTF_8)))) {
                throw new IllegalArgumentException(
                        "skill entry content hash does not match package hash facts");
            }
        }
        this.resourceContents = immutableResourceContents(
                manifest, hashes, resourceContents, requireCompleteContents);
    }

    public Map<String, byte[]> getResourceContents() {
        Map<String, byte[]> copy = new LinkedHashMap<String, byte[]>();
        for (Map.Entry<String, byte[]> entry : resourceContents.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().clone());
        }
        return Collections.unmodifiableMap(copy);
    }

    private Map<String, byte[]> immutableResourceContents(
            SkillManifest manifest, Map<String, String> hashes,
            Map<String, byte[]> contents, boolean requireCompleteContents) {
        if (contents == null) {
            throw new IllegalArgumentException(
                    "skill resource contents must not be null");
        }
        if (!requireCompleteContents && contents.isEmpty()) {
            return Collections.emptyMap();
        }
        if (!contents.keySet().equals(manifest.getResourcePaths())) {
            throw new IllegalArgumentException(
                    "skill resource contents must exactly match manifest resources");
        }
        Map<String, byte[]> copy = new LinkedHashMap<String, byte[]>();
        for (Map.Entry<String, byte[]> entry : contents.entrySet()) {
            String path = DomainText.require(
                    entry.getKey(), "skill resource content path", 500);
            byte[] bytes = entry.getValue();
            if (bytes == null) {
                throw new IllegalArgumentException(
                        "skill resource content must not be null");
            }
            String expectedHash = hashes.get(path);
            if (expectedHash == null
                    || !expectedHash.equals(CanonicalHashing.sha256(bytes))) {
                throw new IllegalArgumentException(
                        "skill resource content hash does not match package hash facts");
            }
            copy.put(path, bytes.clone());
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * 按冻结 Stage 期望的 packageHash 校验并展开 Skill entry 为 prompt。
     *
     * <p>与 {@link CommandDefinition#resolve} 对称：校验内容 hash 未漂移、
     * 把 {@code $ARGUMENTS} 替换为用户参数、校验展开长度上限，返回
     * {@link ResolvedCommandBinding}。
     *
     * @param expectedPackageHash 冻结 Stage Snapshot 记录的期望 package hash
     * @param arguments 用户传入的参数文本，可为 {@code null}
     * @return 展开后的不可变 Command Binding
     * @throws CommandResolutionException hash 不匹配或展开超长
     */
    public ResolvedCommandBinding resolve(
            String expectedPackageHash, String arguments) {
        String expected = DomainText.requireSha256(
                expectedPackageHash, "expected skill package hash");
        if (!packageHash.equals(expected)) {
            throw new CommandResolutionException(
                    "WORKBENCH_SKILL_CONTENT_CHANGED",
                    "skill content no longer matches the frozen stage definition");
        }
        String normalizedArguments = arguments == null ? "" : arguments.trim();
        String expandedPrompt = entryContent.replace(
                CommandDefinition.ARGUMENTS_PLACEHOLDER, normalizedArguments);
        if (expandedPrompt.length() > CommandDefinition.MAX_EXPANDED_PROMPT_LENGTH) {
            throw new CommandResolutionException(
                    "WORKBENCH_SKILL_EXPANSION_TOO_LARGE",
                    "expanded skill prompt exceeds the allowed size");
        }
        return new ResolvedCommandBinding(
                manifest.getId(), manifest.getVersion(),
                packageHash, expandedPrompt);
    }
}
