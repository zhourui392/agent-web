package com.example.agentweb.app.workbench.admin;

import com.example.agentweb.domain.capability.McpServerCatalog;
import com.example.agentweb.domain.capability.McpServerDefinition;
import com.example.agentweb.domain.capability.SkillCatalog;
import com.example.agentweb.domain.capability.SkillPackage;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PhaseCapabilityProfileAdminRepository;
import com.example.agentweb.domain.workbench.PhaseCapabilityProfileEntry;
import com.example.agentweb.domain.workbench.PhaseCapabilityReference;
import com.example.agentweb.domain.workbench.PhaseCapabilityType;
import com.example.agentweb.domain.workbench.WorkbenchAdministrator;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 管理后台 Phase Capability Profile CRUD 编排。
 *
 * <p>管理员可查看四阶段 Profile、从可信 Catalog 选择 Skill / MCP 分配到阶段，
 * 以及更新能力列表。更新后 Profile 版本自增，触发已有 per-workbench override
 * 的 drift 重置（已有行为）。</p>
 *
 * @author alex
 * @since 2026-08-02
 */
@Service
@Transactional(readOnly = true)
public class AdminPhaseCapabilityAppService {

    private final PhaseCapabilityProfileAdminRepository profileRepository;
    private final SkillCatalog skillCatalog;
    private final McpServerCatalog mcpServerCatalog;
    private final Clock clock;

    public AdminPhaseCapabilityAppService(
            PhaseCapabilityProfileAdminRepository profileRepository,
            SkillCatalog skillCatalog,
            McpServerCatalog mcpServerCatalog,
            Clock clock) {
        this.profileRepository = Objects.requireNonNull(
                profileRepository, "profileRepository");
        this.skillCatalog = Objects.requireNonNull(
                skillCatalog, "skillCatalog");
        this.mcpServerCatalog = Objects.requireNonNull(
                mcpServerCatalog, "mcpServerCatalog");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public List<AdminPhaseCapabilityProfileView> listProfiles() {
        return profileRepository.findAll().stream()
                .map(AdminPhaseCapabilityProfileView::from)
                .toList();
    }

    public AdminPhaseCapabilityProfileView getProfile(WorkbenchPhase phase) {
        return profileRepository.findByPhase(phase)
                .map(AdminPhaseCapabilityProfileView::from)
                .orElseThrow(() -> new IllegalArgumentException(
                        "phase capability profile not found: " + phase));
    }

    public AdminCapabilityCatalogView listCatalog() {
        List<AdminCapabilityCatalogView.CatalogEntry> skills =
                new ArrayList<AdminCapabilityCatalogView.CatalogEntry>();
        for (SkillPackage pkg : skillCatalog.discover()) {
            skills.add(new AdminCapabilityCatalogView.CatalogEntry(
                    pkg.getManifest().getId(),
                    pkg.getManifest().getVersion(),
                    pkg.getManifest().getDescription(),
                    new ArrayList<>(pkg.getManifest()
                            .getCompatibleRuntimes())));
        }
        List<AdminCapabilityCatalogView.CatalogEntry> mcpServers =
                new ArrayList<AdminCapabilityCatalogView.CatalogEntry>();
        for (McpServerDefinition def : mcpServerCatalog.discover()) {
            mcpServers.add(new AdminCapabilityCatalogView.CatalogEntry(
                    def.getId(),
                    def.getVersion(),
                    def.getDescription(),
                    new ArrayList<>(def.getCompatibleRuntimes())));
        }
        return AdminCapabilityCatalogView.of(skills, mcpServers);
    }

    @Transactional
    public AdminPhaseCapabilityProfileView updateProfile(
            WorkbenchPhase phase,
            List<CapabilityReferenceInput> capabilities,
            long expectedVersion,
            WorkbenchAdministrator admin) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(admin, "admin");
        if (capabilities.isEmpty()) {
            throw new IllegalArgumentException(
                    "phase capability profile must contain at least one capability");
        }
        PhaseCapabilityProfileEntry current = profileRepository
                .findByPhase(phase)
                .orElseThrow(() -> new IllegalArgumentException(
                        "phase capability profile not found: " + phase));
        List<PhaseCapabilityReference> references =
                new ArrayList<PhaseCapabilityReference>();
        for (CapabilityReferenceInput input : capabilities) {
            references.add(new PhaseCapabilityReference(
                    input.id(),
                    PhaseCapabilityType.valueOf(input.type()),
                    input.required()));
        }
        OwnerReference actor = OwnerReference.of(
                admin.getActorId(), admin.getActorName());
        PhaseCapabilityProfileEntry updated = current.updateCapabilities(
                expectedVersion, references, actor, clock.instant());
        profileRepository.replace(updated, expectedVersion);
        return AdminPhaseCapabilityProfileView.from(updated);
    }

    /**
     * 能力引用输入 DTO（接口层也可直接使用）。
     */
    public record CapabilityReferenceInput(
            String id, String type, boolean required) {
    }
}
