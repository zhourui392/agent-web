package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.workbench.PhaseCapabilityProfile;
import com.example.agentweb.domain.workbench.PhaseCapabilityProfileCatalog;
import com.example.agentweb.domain.workbench.PhaseCapabilityReference;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 数据库支持的 Phase Capability Profile Catalog 读端口实现。
 *
 * <p>替代 {@code FileSystemPhaseCapabilityProfileCatalog}，从
 * {@code workbench_phase_capability_profile} 表读取四阶段 Profile。
 * 启动时由 {@link PhaseCapabilityProfileSeed} 保证种子数据存在。</p>
 *
 * @author alex
 * @since 2026-08-02
 */
@Component
public class SqlitePhaseCapabilityProfileCatalog
        implements PhaseCapabilityProfileCatalog {

    private static final String COLUMNS = "phase, profile_id, profile_version, "
            + "profile_hash, capabilities_json";

    private final JdbcTemplate jdbc;
    private final WorkbenchJsonCodec codec;

    public SqlitePhaseCapabilityProfileCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.codec = new WorkbenchJsonCodec();
    }

    @Override
    public PhaseCapabilityProfile requireProfile(WorkbenchPhase phase) {
        if (phase == null) {
            throw new IllegalArgumentException(
                    "workbench phase must not be null");
        }
        List<ProfileRow> rows = jdbc.query(
                "SELECT " + COLUMNS
                        + " FROM workbench_phase_capability_profile"
                        + " WHERE phase=?",
                this::readRow, phase.name());
        if (rows.isEmpty()) {
            throw new IllegalStateException(
                    "phase capability profile is not configured for phase: "
                            + phase);
        }
        ProfileRow row = rows.get(0);
        List<PhaseCapabilityReference> references =
                codec.readPhaseCapabilityReferences(row.capabilitiesJson);
        return PhaseCapabilityProfile.restore(
                row.profileId, row.profileVersion, row.profileHash,
                WorkbenchPhase.valueOf(row.phase), references);
    }

    private ProfileRow readRow(ResultSet rs, int rowNum) throws SQLException {
        return new ProfileRow(
                rs.getString("phase"),
                rs.getString("profile_id"),
                rs.getString("profile_version"),
                rs.getString("profile_hash"),
                rs.getString("capabilities_json"));
    }

    private static final class ProfileRow {
        final String phase;
        final String profileId;
        final String profileVersion;
        final String profileHash;
        final String capabilitiesJson;

        ProfileRow(String phase, String profileId, String profileVersion,
                   String profileHash, String capabilitiesJson) {
            this.phase = phase;
            this.profileId = profileId;
            this.profileVersion = profileVersion;
            this.profileHash = profileHash;
            this.capabilitiesJson = capabilitiesJson;
        }
    }
}
