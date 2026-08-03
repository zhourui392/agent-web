package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PhaseCapabilityProfile;
import com.example.agentweb.domain.workbench.PhaseCapabilityProfileAdminRepository;
import com.example.agentweb.domain.workbench.PhaseCapabilityProfileEntry;
import com.example.agentweb.domain.workbench.PhaseCapabilityReference;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Phase Capability Profile 管理后台写端口的 SQLite 实现。
 *
 * <p>每阶段一行，{@code replace} 使用乐观锁（WHERE version=?）。
 * 读取时通过 {@link WorkbenchJsonCodec} 反序列化 capabilities JSON，
 * 并用 {@link PhaseCapabilityProfile#restore} 恢复含 hash 校验的领域对象。</p>
 *
 * @author alex
 * @since 2026-08-02
 */
@Repository
public class SqlitePhaseCapabilityProfileAdminRepository
        implements PhaseCapabilityProfileAdminRepository {

    private static final String COLUMNS = "phase, profile_id, profile_version, "
            + "profile_hash, capabilities_json, updated_by_id, updated_by_name, "
            + "updated_at, version";

    private final JdbcTemplate jdbc;
    private final WorkbenchJsonCodec codec;

    public SqlitePhaseCapabilityProfileAdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.codec = new WorkbenchJsonCodec();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PhaseCapabilityProfileEntry> findAll() {
        return jdbc.query(
                "SELECT " + COLUMNS
                        + " FROM workbench_phase_capability_profile"
                        + " ORDER BY phase",
                this::readEntry);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PhaseCapabilityProfileEntry> findByPhase(
            WorkbenchPhase phase) {
        if (phase == null) {
            throw new IllegalArgumentException(
                    "phase must not be null");
        }
        List<PhaseCapabilityProfileEntry> rows = jdbc.query(
                "SELECT " + COLUMNS
                        + " FROM workbench_phase_capability_profile"
                        + " WHERE phase=?",
                this::readEntry, phase.name());
        return rows.isEmpty()
                ? Optional.<PhaseCapabilityProfileEntry>empty()
                : Optional.of(rows.get(0));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replace(
            PhaseCapabilityProfileEntry entry,
            long expectedStorageVersion) {
        if (entry == null) {
            throw new IllegalArgumentException(
                    "phase capability profile entry must not be null");
        }
        PhaseCapabilityProfile profile = entry.getProfile();
        String capabilitiesJson = codec.writePhaseCapabilityReferences(
                profile.getCapabilities());
        int rows;
        try {
            rows = jdbc.update(
                    "UPDATE workbench_phase_capability_profile SET "
                            + "profile_id=?, profile_version=?, profile_hash=?, "
                            + "capabilities_json=?, updated_by_id=?, "
                            + "updated_by_name=?, updated_at=?, version=? "
                            + "WHERE phase=? AND version=?",
                    profile.getProfileId(), profile.getProfileVersion(),
                    profile.getProfileHash(), capabilitiesJson,
                    entry.getUpdatedBy().getOwnerId(),
                    entry.getUpdatedBy().getOwnerName(),
                    entry.getUpdatedAt().toEpochMilli(),
                    entry.getStorageVersion(),
                    profile.getPhase().name(),
                    expectedStorageVersion);
        } catch (DataAccessException ex) {
            throw new IllegalStateException(
                    "phase capability profile could not be updated: "
                            + profile.getPhase(),
                    ex);
        }
        if (rows != 1) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.VERSION_CONFLICT,
                    "stale or missing phase capability profile: "
                            + profile.getPhase());
        }
    }

    /**
     * 供 Seed 使用：插入初始行（若不存在）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void insertIfAbsent(PhaseCapabilityProfileEntry entry) {
        PhaseCapabilityProfile profile = entry.getProfile();
        String capabilitiesJson = codec.writePhaseCapabilityReferences(
                profile.getCapabilities());
        try {
            jdbc.update(
                    "INSERT INTO workbench_phase_capability_profile (" + COLUMNS
                            + ") VALUES (?,?,?,?,?,?,?,?,?) "
                            + "ON CONFLICT(phase) DO NOTHING",
                    profile.getPhase().name(),
                    profile.getProfileId(),
                    profile.getProfileVersion(),
                    profile.getProfileHash(),
                    capabilitiesJson,
                    entry.getUpdatedBy().getOwnerId(),
                    entry.getUpdatedBy().getOwnerName(),
                    entry.getUpdatedAt().toEpochMilli(),
                    entry.getStorageVersion());
        } catch (DataAccessException ex) {
            throw new IllegalStateException(
                    "phase capability profile seed could not be inserted: "
                            + profile.getPhase(),
                    ex);
        }
    }

    @Transactional(readOnly = true)
    public int count() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workbench_phase_capability_profile",
                Integer.class);
        return count == null ? 0 : count;
    }

    private PhaseCapabilityProfileEntry readEntry(
            ResultSet rs, int rowNum) throws SQLException {
        List<PhaseCapabilityReference> references =
                codec.readPhaseCapabilityReferences(
                        rs.getString("capabilities_json"));
        PhaseCapabilityProfile profile = PhaseCapabilityProfile.restore(
                rs.getString("profile_id"),
                rs.getString("profile_version"),
                rs.getString("profile_hash"),
                WorkbenchPhase.valueOf(rs.getString("phase")),
                references);
        return PhaseCapabilityProfileEntry.restore(
                profile,
                OwnerReference.of(
                        rs.getString("updated_by_id"),
                        rs.getString("updated_by_name")),
                Instant.ofEpochMilli(rs.getLong("updated_at")),
                rs.getLong("version"));
    }
}
