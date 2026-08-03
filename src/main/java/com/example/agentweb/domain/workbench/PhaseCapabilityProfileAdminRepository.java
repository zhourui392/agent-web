package com.example.agentweb.domain.workbench;

import java.util.List;
import java.util.Optional;

/**
 * Phase Capability Profile 的管理后台写端口。
 *
 * <p>支持管理员对四阶段 Profile 的列表查询、单阶段查询和替换更新。
 * 读端口 {@link PhaseCapabilityProfileCatalog} 保持不变。</p>
 *
 * @author alex
 * @since 2026-08-02
 */
public interface PhaseCapabilityProfileAdminRepository {

    List<PhaseCapabilityProfileEntry> findAll();

    Optional<PhaseCapabilityProfileEntry> findByPhase(WorkbenchPhase phase);

    void replace(PhaseCapabilityProfileEntry entry, long expectedStorageVersion);
}
