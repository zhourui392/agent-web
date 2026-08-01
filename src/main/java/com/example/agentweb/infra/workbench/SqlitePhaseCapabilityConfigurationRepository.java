package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.workbench.CapabilityOverride;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PhaseCapabilityConfiguration;
import com.example.agentweb.domain.workbench.PhaseCapabilityConfigurationRepository;
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
 * @author alex
 * @since 2026-08-01
 */
@Repository
public class SqlitePhaseCapabilityConfigurationRepository
        implements PhaseCapabilityConfigurationRepository {

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
        if (configuration.getVersion() == 0L) {
            insert(configuration);
            return;
        }
        update(configuration);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PhaseCapabilityConfiguration> find(
            WorkbenchId workbenchId, WorkbenchPhase phase) {
        requireIdentity(workbenchId, phase);
        List<ConfigurationRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM workbench_phase_capability_config "
                        + "WHERE workbench_id=? AND phase=?",
                this::read, workbenchId.getValue(), phase.name());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        ConfigurationRow row = rows.get(0);
        try {
            CapabilityOverride override = codec.readCapabilityOverride(row.overrideJson);
            return Optional.of(PhaseCapabilityConfiguration.restore(
                    WorkbenchId.of(row.workbenchId), WorkbenchPhase.valueOf(row.phase),
                    row.baseProfileId, row.baseProfileVersion, override,
                    OwnerReference.of(row.updatedById, row.updatedByName),
                    row.updatedAt, row.version, restorationPolicy(
                            WorkbenchPhase.valueOf(row.phase), override)));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "corrupt phase capability configuration " + row.workbenchId
                            + ":" + row.phase + ": " + ex.getMessage(), ex);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(WorkbenchId workbenchId, WorkbenchPhase phase,
                       long expectedVersion) {
        requireIdentity(workbenchId, phase);
        if (expectedVersion < 0L) {
            throw new IllegalArgumentException(
                    "capability configuration expected version must not be negative");
        }
        int rows = jdbc.update(
                "DELETE FROM workbench_phase_capability_config "
                        + "WHERE workbench_id=? AND phase=? AND version=?",
                workbenchId.getValue(), phase.name(), expectedVersion);
        if (rows != 1) {
            throw versionConflict(workbenchId.getValue() + ":" + phase);
        }
    }

    private void insert(PhaseCapabilityConfiguration configuration) {
        try {
            jdbc.update("INSERT INTO workbench_phase_capability_config (" + COLUMNS
                            + ") VALUES (?,?,?,?,?,?,?,?,?)",
                    configuration.getWorkbenchId().getValue(),
                    configuration.getPhase().name(), configuration.getBaseProfileId(),
                    configuration.getBaseProfileVersion(),
                    codec.writeCapabilityOverride(configuration.getOverride()),
                    configuration.getUpdatedBy().getOwnerId(),
                    configuration.getUpdatedBy().getOwnerName(),
                    configuration.getUpdatedAt().toEpochMilli(),
                    configuration.getVersion());
        } catch (DataAccessException ex) {
            throw new IllegalStateException(
                    "phase capability configuration could not be added: "
                            + identity(configuration), ex);
        }
    }

    private void update(PhaseCapabilityConfiguration configuration) {
        long expectedVersion = configuration.getVersion() - 1L;
        try {
            int rows = jdbc.update(
                    "UPDATE workbench_phase_capability_config SET override_json=?, "
                            + "updated_by_id=?, updated_by_name=?, updated_at=?, version=? "
                            + "WHERE workbench_id=? AND phase=? AND version=? "
                            + "AND base_profile_id=? AND base_profile_version=?",
                    codec.writeCapabilityOverride(configuration.getOverride()),
                    configuration.getUpdatedBy().getOwnerId(),
                    configuration.getUpdatedBy().getOwnerName(),
                    configuration.getUpdatedAt().toEpochMilli(),
                    configuration.getVersion(),
                    configuration.getWorkbenchId().getValue(),
                    configuration.getPhase().name(), expectedVersion,
                    configuration.getBaseProfileId(),
                    configuration.getBaseProfileVersion());
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
