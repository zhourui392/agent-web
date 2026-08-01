package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.workbench.CapabilityOverride;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PhaseCapabilityConfiguration;
import com.example.agentweb.domain.workbench.PhaseCapabilityConfigurationRepository;
import com.example.agentweb.domain.workbench.PhaseCapabilityConfigurationState;
import com.example.agentweb.domain.workbench.PhaseCapabilityOverridePolicy;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/**
 * Phase Capability Configuration 的 SQLite 写侧 Repository。
 *
 * <p>兼容既有表的零基 version 存储：领域/API token 恒为数据库 version + 1；
 * 删除写入 JSON null tombstone 并递增 token，不物理删除生命周期版本。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Repository
public class SqlitePhaseCapabilityConfigurationRepository
        implements PhaseCapabilityConfigurationRepository {

    private static final String ABSENT_OVERRIDE_JSON = "null";

    private static final String COLUMNS = "workbench_id, phase, base_profile_id, "
            + "base_profile_version, override_json, updated_by_id, updated_by_name, "
            + "updated_at, version";

    private final JdbcTemplate jdbc;
    private final WorkbenchJsonCodec codec;

    public SqlitePhaseCapabilityConfigurationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.codec = new WorkbenchJsonCodec();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(PhaseCapabilityConfiguration configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException(
                    "phase capability configuration must not be null");
        }
        if (configuration.getVersion() == 1L) {
            insert(configuration);
            return;
        }
        update(configuration);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PhaseCapabilityConfiguration> find(
            WorkbenchId workbenchId, WorkbenchPhase phase) {
        return findState(workbenchId, phase).getConfiguration();
    }

    @Override
    @Transactional(readOnly = true)
    public PhaseCapabilityConfigurationState findState(
            WorkbenchId workbenchId, WorkbenchPhase phase) {
        requireIdentity(workbenchId, phase);
        List<ConfigurationRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM workbench_phase_capability_config "
                        + "WHERE workbench_id=? AND phase=?",
                this::read, workbenchId.getValue(), phase.name());
        if (rows.isEmpty()) {
            return PhaseCapabilityConfigurationState.initiallyAbsent(
                    workbenchId, phase);
        }
        ConfigurationRow row = rows.get(0);
        long publicVersion = publicVersion(row.version);
        if (ABSENT_OVERRIDE_JSON.equals(row.overrideJson)) {
            return PhaseCapabilityConfigurationState.absent(
                    workbenchId, phase, publicVersion);
        }
        try {
            CapabilityOverride override = codec.readCapabilityOverride(row.overrideJson);
            return PhaseCapabilityConfigurationState.present(
                    PhaseCapabilityConfiguration.restore(
                    WorkbenchId.of(row.workbenchId), WorkbenchPhase.valueOf(row.phase),
                    row.baseProfileId, row.baseProfileVersion, override,
                    OwnerReference.of(row.updatedById, row.updatedByName),
                    row.updatedAt, publicVersion, restorationPolicy(
                            WorkbenchPhase.valueOf(row.phase), override)));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "corrupt phase capability configuration " + row.workbenchId
                            + ":" + row.phase + ": " + ex.getMessage(), ex);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long delete(WorkbenchId workbenchId, WorkbenchPhase phase,
                       long expectedVersion) {
        requireIdentity(workbenchId, phase);
        if (expectedVersion < 0L) {
            throw new IllegalArgumentException(
                    "capability configuration expected version is invalid");
        }
        if (expectedVersion == Long.MAX_VALUE) {
            throw versionConflict(workbenchId.getValue() + ":" + phase);
        }
        int rows = jdbc.update(
                "UPDATE workbench_phase_capability_config "
                        + "SET override_json=?, version=? "
                        + "WHERE workbench_id=? AND phase=? AND version=? "
                        + "AND override_json<>?",
                ABSENT_OVERRIDE_JSON, expectedVersion,
                workbenchId.getValue(), phase.name(), expectedVersion - 1L,
                ABSENT_OVERRIDE_JSON);
        if (rows != 1) {
            throw versionConflict(workbenchId.getValue() + ":" + phase);
        }
        return expectedVersion + 1L;
    }

    private void insert(PhaseCapabilityConfiguration configuration) {
        try {
            int rows = jdbc.update(
                    "INSERT INTO workbench_phase_capability_config (" + COLUMNS
                            + ") VALUES (?,?,?,?,?,?,?,?,?) "
                            + "ON CONFLICT(workbench_id, phase) DO NOTHING",
                    configuration.getWorkbenchId().getValue(),
                    configuration.getPhase().name(), configuration.getBaseProfileId(),
                    configuration.getBaseProfileVersion(),
                    codec.writeCapabilityOverride(configuration.getOverride()),
                    configuration.getUpdatedBy().getOwnerId(),
                    configuration.getUpdatedBy().getOwnerName(),
                    configuration.getUpdatedAt().toEpochMilli(),
                    configuration.getVersion() - 1L);
            if (rows != 1) {
                throw versionConflict(identity(configuration));
            }
        } catch (WorkbenchDomainException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            throw new IllegalStateException(
                    "phase capability configuration could not be added: "
                            + identity(configuration), ex);
        }
    }

    private void update(PhaseCapabilityConfiguration configuration) {
        long expectedStorageVersion = configuration.getVersion() - 2L;
        try {
            int rows = jdbc.update(
                    "UPDATE workbench_phase_capability_config SET "
                            + "base_profile_id=?, base_profile_version=?, override_json=?, "
                            + "updated_by_id=?, updated_by_name=?, updated_at=?, version=? "
                            + "WHERE workbench_id=? AND phase=? AND version=?",
                    configuration.getBaseProfileId(),
                    configuration.getBaseProfileVersion(),
                    codec.writeCapabilityOverride(configuration.getOverride()),
                    configuration.getUpdatedBy().getOwnerId(),
                    configuration.getUpdatedBy().getOwnerName(),
                    configuration.getUpdatedAt().toEpochMilli(),
                    configuration.getVersion() - 1L,
                    configuration.getWorkbenchId().getValue(),
                    configuration.getPhase().name(), expectedStorageVersion);
            if (rows != 1) {
                throw versionConflict(identity(configuration));
            }
        } catch (WorkbenchDomainException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            throw new IllegalStateException(
                    "phase capability configuration could not be updated: "
                            + identity(configuration), ex);
        }
    }

    private ConfigurationRow read(ResultSet rs, int rowNumber) throws SQLException {
        return new ConfigurationRow(
                rs.getString("workbench_id"), rs.getString("phase"),
                rs.getString("base_profile_id"),
                rs.getString("base_profile_version"),
                rs.getString("override_json"), rs.getString("updated_by_id"),
                rs.getString("updated_by_name"),
                Instant.ofEpochMilli(rs.getLong("updated_at")),
                rs.getLong("version"));
    }

    private long publicVersion(long storageVersion) {
        if (storageVersion < 0L || storageVersion == Long.MAX_VALUE) {
            throw new IllegalStateException(
                    "capability configuration storage version is invalid");
        }
        return storageVersion + 1L;
    }

    private PhaseCapabilityOverridePolicy restorationPolicy(
            WorkbenchPhase phase, CapabilityOverride override) {
        HashSet<String> skills = new HashSet<String>(
                override.getAddedOptionalSkillIds());
        skills.addAll(override.getRemovedOptionalSkillIds());
        return PhaseCapabilityOverridePolicy.constrainedTo(
                phase, skills, override.getSelectedOptionalMcpIds(),
                override.getSelectedOptionalRuleIds(),
                java.util.Collections.<String>emptySet());
    }

    private void requireIdentity(WorkbenchId workbenchId, WorkbenchPhase phase) {
        if (workbenchId == null || phase == null) {
            throw new IllegalArgumentException(
                    "capability configuration workbench and phase must not be null");
        }
    }

    private String identity(PhaseCapabilityConfiguration configuration) {
        return configuration.getWorkbenchId().getValue() + ":"
                + configuration.getPhase();
    }

    private WorkbenchDomainException versionConflict(String identity) {
        return new WorkbenchDomainException(
                WorkbenchErrorCode.VERSION_CONFLICT,
                "stale or missing phase capability configuration: " + identity);
    }

    private static final class ConfigurationRow {
        private final String workbenchId;
        private final String phase;
        private final String baseProfileId;
        private final String baseProfileVersion;
        private final String overrideJson;
        private final String updatedById;
        private final String updatedByName;
        private final Instant updatedAt;
        private final long version;

        private ConfigurationRow(
                String workbenchId, String phase, String baseProfileId,
                String baseProfileVersion, String overrideJson,
                String updatedById, String updatedByName,
                Instant updatedAt, long version) {
            this.workbenchId = workbenchId;
            this.phase = phase;
            this.baseProfileId = baseProfileId;
            this.baseProfileVersion = baseProfileVersion;
            this.overrideJson = overrideJson;
            this.updatedById = updatedById;
            this.updatedByName = updatedByName;
            this.updatedAt = updatedAt;
            this.version = version;
        }
    }
}
