package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PhaseCapabilityProfile;
import com.example.agentweb.domain.workbench.PhaseCapabilityProfileEntry;
import com.example.agentweb.domain.workbench.PhaseCapabilityReference;
import com.example.agentweb.domain.workbench.PhaseCapabilityType;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Workbench 四阶段 Phase Capability Profile 种子数据。
 *
 * <p>启动时若 {@code workbench_phase_capability_profile} 表为空，
 * 插入 4 个阶段的初始 Profile。内容等同迁移前的文件系统 YAML manifest。</p>
 *
 * @author alex
 * @since 2026-08-02
 */
@Component
public class PhaseCapabilityProfileSeed {

    private static final Logger log = LoggerFactory.getLogger(
            PhaseCapabilityProfileSeed.class);

    private static final OwnerReference SYSTEM_OWNER =
            OwnerReference.of("system", "System Seed");
    private static final String INITIAL_VERSION = "1";
    private static final long INITIAL_STORAGE_VERSION = 1L;

    private final SqlitePhaseCapabilityProfileAdminRepository repository;

    public PhaseCapabilityProfileSeed(
            SqlitePhaseCapabilityProfileAdminRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void seedIfEmpty() {
        if (repository.count() > 0) {
            return;
        }
        log.info("Seeding workbench phase capability profiles...");
        Instant now = Instant.now();
        for (WorkbenchPhase phase : WorkbenchPhase.values()) {
            List<PhaseCapabilityReference> capabilities =
                    seedCapabilities(phase);
            PhaseCapabilityProfile profile = PhaseCapabilityProfile.create(
                    profileId(phase), INITIAL_VERSION, phase, capabilities);
            PhaseCapabilityProfileEntry entry = PhaseCapabilityProfileEntry
                    .restore(profile, SYSTEM_OWNER, now,
                            INITIAL_STORAGE_VERSION);
            repository.insertIfAbsent(entry);
        }
        log.info("Seeded {} workbench phase capability profiles",
                WorkbenchPhase.values().length);
    }

    private static String profileId(WorkbenchPhase phase) {
        return "workbench-" + phase.name().toLowerCase()
                .replace('_', '-');
    }

    private static List<PhaseCapabilityReference> seedCapabilities(
            WorkbenchPhase phase) {
        List<PhaseCapabilityReference> capabilities =
                new ArrayList<PhaseCapabilityReference>();
        switch (phase) {
            case REQUIREMENT_ANALYSIS:
                capabilities.add(ref("platform/workbench-safety",
                        PhaseCapabilityType.RULE, true));
                capabilities.add(ref("workbench/read-only-requirement-analysis",
                        PhaseCapabilityType.RULE, true));
                capabilities.add(ref("requirement-analysis",
                        PhaseCapabilityType.SKILL, false));
                capabilities.add(ref("code-search",
                        PhaseCapabilityType.SKILL, false));
                capabilities.add(ref("service-navigation",
                        PhaseCapabilityType.SKILL, false));
                capabilities.add(ref("repository-query",
                        PhaseCapabilityType.MCP_SERVER, false));
                break;
            case SOLUTION_DESIGN:
                capabilities.add(ref("platform/workbench-safety",
                        PhaseCapabilityType.RULE, true));
                capabilities.add(ref("workbench/read-only-solution-design",
                        PhaseCapabilityType.RULE, true));
                capabilities.add(ref("domain-modeling-audit",
                        PhaseCapabilityType.SKILL, false));
                capabilities.add(ref("java-ddd-design",
                        PhaseCapabilityType.SKILL, false));
                capabilities.add(ref("contract-analysis",
                        PhaseCapabilityType.SKILL, false));
                capabilities.add(ref("test-design",
                        PhaseCapabilityType.SKILL, false));
                capabilities.add(ref("repository-query",
                        PhaseCapabilityType.MCP_SERVER, false));
                break;
            case IMPLEMENT_TEST:
                capabilities.add(ref("platform/workbench-safety",
                        PhaseCapabilityType.RULE, true));
                capabilities.add(ref("workbench/tdd-minimal-change",
                        PhaseCapabilityType.RULE, true));
                capabilities.add(ref("java-tdd",
                        PhaseCapabilityType.SKILL, false));
                capabilities.add(ref("target-development",
                        PhaseCapabilityType.SKILL, false));
                capabilities.add(ref("regression-test",
                        PhaseCapabilityType.SKILL, false));
                capabilities.add(ref("repository-query",
                        PhaseCapabilityType.MCP_SERVER, false));
                capabilities.add(ref("local-test-runner",
                        PhaseCapabilityType.MCP_SERVER, false));
                break;
            case REVIEW_REFACTOR:
                capabilities.add(ref("platform/workbench-safety",
                        PhaseCapabilityType.RULE, true));
                capabilities.add(ref("workbench/human-opinion-first",
                        PhaseCapabilityType.RULE, true));
                capabilities.add(ref("domain-modeling-audit",
                        PhaseCapabilityType.SKILL, false));
                capabilities.add(ref("review-assistant",
                        PhaseCapabilityType.SKILL, false));
                capabilities.add(ref("refactor-assistant",
                        PhaseCapabilityType.SKILL, false));
                capabilities.add(ref("release-verification",
                        PhaseCapabilityType.SKILL, false));
                capabilities.add(ref("repository-query",
                        PhaseCapabilityType.MCP_SERVER, false));
                break;
            default:
                throw new IllegalStateException(
                        "unsupported workbench phase: " + phase);
        }
        return capabilities;
    }

    private static PhaseCapabilityReference ref(
            String id, PhaseCapabilityType type, boolean required) {
        return new PhaseCapabilityReference(id, type, required);
    }
}
