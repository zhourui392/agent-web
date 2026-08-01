package com.example.agentweb.infra.capability;

import com.example.agentweb.domain.capability.CapabilityCatalogException;
import com.example.agentweb.domain.capability.RuleDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公共 Rule Catalog 的 logical ID、use case、Manifest 与文件安全测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class FileSystemRuleCatalogTest {

    private static final String COMMON_RULE_ROOT =
            "src/main/resources/capability/rules";
    private static final List<String> WORKBENCH_REQUIRED_RULE_IDS = Arrays.asList(
            "platform/workbench-safety",
            "workbench/read-only-requirement-analysis",
            "workbench/read-only-solution-design",
            "workbench/tdd-minimal-change",
            "workbench/human-opinion-first");

    @TempDir
    Path tempDir;

    @Test
    void shouldResolveExactLogicalIdAndParsePublicManifestFacts() throws Exception {
        writeRule(
                "workbench-analysis", "workbench/read-only-requirement-analysis",
                "2.1.0", "PLATFORM", true,
                "需求分析阶段只读并先核实事实",
                Collections.singletonList("REQUIREMENT_ANALYSIS"),
                "rules", "rules.md", true);

        RuleDefinition definition = new FileSystemRuleCatalog(tempDir)
                .resolveById("workbench/read-only-requirement-analysis");

        assertEquals("workbench/read-only-requirement-analysis", definition.getId());
        assertEquals("2.1.0", definition.getVersion());
        assertEquals("PLATFORM", definition.getSource());
        assertTrue(definition.isMandatory());
        assertEquals("需求分析阶段只读并先核实事实", definition.getSummary());
        assertEquals(Collections.singleton("REQUIREMENT_ANALYSIS"),
                definition.getApplicableUseCases());
        assertEquals("rule content", definition.requireResource("rules").getContent());
        assertTrue(definition.getContentHash().matches("[a-f0-9]{64}"));
    }

    @Test
    void exactIdResolutionShouldAllowMultipleRulesForTheSameWorkbenchUseCase()
            throws Exception {
        writeRule(
                "workbench-safety", "platform/workbench-safety", "1.0.0",
                "PLATFORM", true, "公共安全边界",
                Collections.singletonList("IMPLEMENT_TEST"),
                "rules", "rules.md", true);
        writeRule(
                "workbench-tdd", "workbench/tdd-minimal-change", "1.0.0",
                "PLATFORM", true, "TDD 与最小修改",
                Collections.singletonList("IMPLEMENT_TEST"),
                "rules", "rules.md", true);

        FileSystemRuleCatalog catalog = new FileSystemRuleCatalog(tempDir);
        RuleDefinition safety = catalog.resolveById("platform/workbench-safety");
        RuleDefinition tdd = catalog.resolveById("workbench/tdd-minimal-change");

        assertTrue(safety.supports("IMPLEMENT_TEST"));
        assertTrue(tdd.supports("IMPLEMENT_TEST"));
        assertEquals("platform/workbench-safety", safety.getId());
        assertEquals("workbench/tdd-minimal-change", tdd.getId());
    }

    @Test
    void useCaseResolutionShouldRemainCompatibleWithHarnessStages() throws Exception {
        writeRule(
                "analysis", "analysis", "1.0.0", "PLATFORM", true,
                "Harness analysis Prompt Pack",
                Collections.singletonList("ANALYSIS"),
                "system", "system.md", true);

        RuleDefinition definition = new FileSystemRuleCatalog(tempDir)
                .resolve("analysis");

        assertEquals("analysis", definition.getId());
        assertTrue(definition.supports("ANALYSIS"));
        assertEquals("rule content",
                definition.requireResource("system").getContent());
    }

    @Test
    void exactIdShouldFailClosedForVersionConflict() throws Exception {
        writeRule(
                "safety-v1", "platform/workbench-safety", "1.0.0",
                "PLATFORM", true, "Safety v1",
                Collections.singletonList("REQUIREMENT_ANALYSIS"),
                "rules", "rules.md", true);
        writeRule(
                "safety-v2", "platform/workbench-safety", "2.0.0",
                "PLATFORM", true, "Safety v2",
                Collections.singletonList("SOLUTION_DESIGN"),
                "rules", "rules.md", true);

        CapabilityCatalogException error = assertThrows(
                CapabilityCatalogException.class,
                () -> new FileSystemRuleCatalog(tempDir)
                        .resolveById("platform/workbench-safety"));

        assertEquals("RULE_DEFINITION_VERSION_CONFLICT", error.getCode());
    }

    @Test
    void workbenchRulesShouldFailClosedForPathEscapeAndMissingResource()
            throws Exception {
        writeRule(
                "escaping", "workbench/tdd-minimal-change", "1.0.0",
                "PLATFORM", true, "Escaping resource",
                Collections.singletonList("IMPLEMENT_TEST"),
                "rules", "../outside.md", false);

        CapabilityCatalogException escape = assertThrows(
                CapabilityCatalogException.class,
                () -> new FileSystemRuleCatalog(tempDir)
                        .resolveById("workbench/tdd-minimal-change"));
        assertEquals("CATALOG_PATH_ESCAPE", escape.getCode());

        Files.delete(tempDir.resolve("escaping/1.0.0/manifest.yml"));
        writeRule(
                "missing", "workbench/human-opinion-first", "1.0.0",
                "PLATFORM", true, "Missing resource",
                Collections.singletonList("REVIEW_REFACTOR"),
                "rules", "missing.md", false);
        CapabilityCatalogException missing = assertThrows(
                CapabilityCatalogException.class,
                () -> new FileSystemRuleCatalog(tempDir)
                        .resolveById("workbench/human-opinion-first"));
        assertEquals("CATALOG_RESOURCE_MISSING", missing.getCode());
    }

    @Test
    void malformedMandatoryFactShouldFailClosedAsManifestInvalid() throws Exception {
        writeRule(
                "invalid", "platform/workbench-safety", "1.0.0",
                "PLATFORM", true, "Invalid mandatory",
                Collections.singletonList("REQUIREMENT_ANALYSIS"),
                "rules", "rules.md", true);
        Path manifest = tempDir.resolve("invalid/1.0.0/manifest.yml");
        String yaml = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8)
                .replace("mandatory: true", "mandatory: sometimes");
        Files.write(manifest, yaml.getBytes(StandardCharsets.UTF_8));

        CapabilityCatalogException error = assertThrows(
                CapabilityCatalogException.class,
                () -> new FileSystemRuleCatalog(tempDir)
                        .resolveById("platform/workbench-safety"));

        assertEquals("CATALOG_MANIFEST_INVALID", error.getCode());
    }

    @Test
    void builtInCommonRootShouldCoverHarnessAndEveryWorkbenchRequiredRuleId() {
        FileSystemRuleCatalog catalog = new FileSystemRuleCatalog(
                Paths.get(COMMON_RULE_ROOT));

        for (String harnessUseCase : Arrays.asList(
                "ANALYSIS", "DESIGN", "IMPLEMENTATION", "DEPLOYMENT")) {
            RuleDefinition definition = catalog.resolve(harnessUseCase);
            assertEquals(harnessUseCase.toLowerCase(java.util.Locale.ROOT),
                    definition.getId());
            assertTrue(definition.supports(harnessUseCase));
            assertEquals(4, definition.getResources().size());
        }
        for (String ruleId : WORKBENCH_REQUIRED_RULE_IDS) {
            RuleDefinition definition = catalog.resolveById(ruleId);
            assertEquals(ruleId, definition.getId());
            assertEquals("1.0.0", definition.getVersion());
            assertEquals("PLATFORM", definition.getSource());
            assertTrue(definition.isMandatory());
            assertFalse(definition.getSummary().trim().isEmpty());
            assertFalse(definition.getResources().isEmpty());
            assertTrue(definition.getContentHash().matches("[a-f0-9]{64}"));
        }
    }

    private Path writeRule(
            String packageName, String id, String version,
            String source, boolean mandatory, String summary,
            List<String> applicableUseCases, String resourceName,
            String resourcePath, boolean writeResource) throws Exception {
        Path packageDir = Files.createDirectories(
                tempDir.resolve(packageName).resolve(version));
        StringBuilder manifest = new StringBuilder()
                .append("schemaVersion: '1'\n")
                .append("id: ").append(id).append('\n')
                .append("version: '").append(version).append("'\n")
                .append("source: ").append(source).append('\n')
                .append("mandatory: ").append(mandatory).append('\n')
                .append("summary: ").append(summary).append('\n')
                .append("applicableUseCases:\n");
        for (String useCase : applicableUseCases) {
            manifest.append("  - ").append(useCase).append('\n');
        }
        manifest.append("resources:\n")
                .append("  ").append(resourceName).append(": ")
                .append(resourcePath).append('\n');
        Files.write(packageDir.resolve("manifest.yml"),
                manifest.toString().getBytes(StandardCharsets.UTF_8));
        if (writeResource) {
            Files.write(packageDir.resolve(resourcePath),
                    "rule content".getBytes(StandardCharsets.UTF_8));
        }
        return packageDir;
    }
}
