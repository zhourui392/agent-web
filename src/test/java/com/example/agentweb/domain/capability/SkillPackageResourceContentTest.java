package com.example.agentweb.domain.capability;

import com.example.agentweb.domain.shared.CanonicalHashing;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Skill 包声明资源正文与 Hash 的不变量测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class SkillPackageResourceContentTest {

    @Test
    void preservesExactDeclaredResourceBytesWithoutExposingMutableArrays() {
        byte[] original = "exact rules".getBytes(StandardCharsets.UTF_8);
        SkillPackage skillPackage = skillPackage(
                Collections.singletonMap("references/rules.md", original));

        original[0] = 'X';
        byte[] firstRead = skillPackage.getResourceContents()
                .get("references/rules.md");
        firstRead[0] = 'Y';

        assertArrayEquals("exact rules".getBytes(StandardCharsets.UTF_8),
                skillPackage.getResourceContents().get("references/rules.md"));
        assertEquals(Collections.singleton("references/rules.md"),
                skillPackage.getResourceContents().keySet());
    }

    @Test
    void rejectsMissingUnexpectedOrHashMismatchedResourceContent() {
        assertThrows(IllegalArgumentException.class,
                () -> skillPackage(Collections.<String, byte[]>emptyMap()));
        assertThrows(IllegalArgumentException.class,
                () -> skillPackage(Collections.singletonMap(
                        "other.md", "exact rules".getBytes(StandardCharsets.UTF_8))));
        assertThrows(IllegalArgumentException.class,
                () -> skillPackage(Collections.singletonMap(
                        "references/rules.md", "changed".getBytes(StandardCharsets.UTF_8))));
        assertThrows(IllegalArgumentException.class,
                () -> skillPackage(
                        Collections.singletonMap("references/rules.md",
                                "exact rules".getBytes(StandardCharsets.UTF_8)),
                        "# Changed entry"));
    }

    private SkillPackage skillPackage(Map<String, byte[]> resourceContents) {
        return skillPackage(resourceContents, "# Java TDD");
    }

    private SkillPackage skillPackage(
            Map<String, byte[]> resourceContents, String entryContent) {
        SkillManifest manifest = new SkillManifest(
                "java-tdd", "1.0.0", "Java TDD",
                set("WORKBENCH_STAGE"), set("java"), set("java-tdd"),
                "SKILL.md", set("references/rules.md"),
                Collections.emptyList(), Collections.emptySet(), set("CODEX"),
                SkillTrustSource.PLATFORM, Collections.emptyList());
        Map<String, String> hashes = new LinkedHashMap<String, String>();
        hashes.put("manifest.yml", CanonicalHashing.sha256("manifest"));
        hashes.put("SKILL.md", CanonicalHashing.sha256("# Java TDD"));
        hashes.put("references/rules.md", CanonicalHashing.sha256("exact rules"));
        return new SkillPackage(manifest, CanonicalHashing.sha256("package"),
                entryContent, hashes, resourceContents);
    }

    private LinkedHashSet<String> set(String value) {
        return new LinkedHashSet<String>(Collections.singleton(value));
    }
}
