package com.example.agentweb.infra.harness;

import com.example.agentweb.app.harness.HarnessArtifactContentView;
import com.example.agentweb.app.harness.HarnessArtifactQueryService;
import com.example.agentweb.domain.harness.ArtifactClassification;
import com.example.agentweb.domain.harness.ArtifactDescriptor;
import com.example.agentweb.domain.harness.ArtifactReference;
import com.example.agentweb.domain.harness.ArtifactStore;
import com.example.agentweb.domain.harness.ArtifactType;
import com.example.agentweb.domain.harness.HarnessStage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * SQLite Artifact 元数据与受控 ArtifactStore 的下载投影。
 *
 * @author alex
 * @since 2026-07-23
 */
@Component
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class SqliteHarnessArtifactQueryService implements HarnessArtifactQueryService {

    private static final String COLUMNS = "artifact_id, artifact_type, version, run_id, stage, "
            + "attempt_number, content_type, size_bytes, sha256, classification, created_by, created_at";

    private final JdbcTemplate jdbc;
    private final ArtifactStore artifactStore;

    public SqliteHarnessArtifactQueryService(JdbcTemplate jdbc, ArtifactStore artifactStore) {
        this.jdbc = jdbc;
        this.artifactStore = artifactStore;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<HarnessArtifactContentView> findLatest(String runId, String artifactId) {
        return content(first(jdbc.query("SELECT " + COLUMNS + " FROM harness_artifact "
                        + "WHERE run_id=? AND artifact_id=? ORDER BY version DESC LIMIT 1",
                this::read, runId, artifactId)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<HarnessArtifactContentView> findFinalReport(String runId) {
        return content(first(jdbc.query("SELECT " + COLUMNS + " FROM harness_artifact "
                        + "WHERE run_id=? AND artifact_type='FINAL_REPORT' "
                        + "ORDER BY version DESC LIMIT 1", this::read, runId)));
    }

    private Optional<HarnessArtifactContentView> content(Optional<ArtifactDescriptor> descriptor) {
        if (!descriptor.isPresent()) {
            return Optional.empty();
        }
        ArtifactDescriptor value = descriptor.get();
        return Optional.of(new HarnessArtifactContentView(value.getArtifactId(), value.getVersion(),
                value.getArtifactType().name(), value.getContentType(), value.getSha256(),
                artifactStore.read(value).copyBytes()));
    }

    private ArtifactDescriptor read(ResultSet rs, int rowNumber) throws SQLException {
        return new ArtifactDescriptor(rs.getString("artifact_id"),
                ArtifactType.valueOf(rs.getString("artifact_type")), rs.getInt("version"),
                rs.getString("run_id"), HarnessStage.valueOf(rs.getString("stage")),
                rs.getInt("attempt_number"), rs.getString("content_type"),
                rs.getLong("size_bytes"), rs.getString("sha256"),
                ArtifactClassification.valueOf(rs.getString("classification")),
                rs.getString("created_by"), Instant.ofEpochMilli(rs.getLong("created_at")),
                Collections.<ArtifactReference>emptyList());
    }

    private Optional<ArtifactDescriptor> first(List<ArtifactDescriptor> values) {
        return values.stream().findFirst();
    }
}
