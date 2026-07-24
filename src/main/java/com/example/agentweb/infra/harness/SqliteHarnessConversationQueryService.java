package com.example.agentweb.infra.harness;

import com.example.agentweb.app.harness.HarnessConversationMessageView;
import com.example.agentweb.app.harness.HarnessConversationQueryService;
import com.example.agentweb.domain.harness.ArtifactClassification;
import com.example.agentweb.domain.harness.ArtifactDescriptor;
import com.example.agentweb.domain.harness.ArtifactReference;
import com.example.agentweb.domain.harness.ArtifactStore;
import com.example.agentweb.domain.harness.ArtifactType;
import com.example.agentweb.domain.harness.HarnessRunNotFoundException;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.StageConversationMessage;
import com.example.agentweb.domain.harness.StageConversationPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户修订事件与 Runtime 主 Artifact 合并后的 SQLite 对话投影。
 *
 * @author alex
 * @since 2026-07-24
 */
@Repository
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
@SuppressWarnings("PMD.ServiceOrDaoClassShouldEndWithImplRule")
public class SqliteHarnessConversationQueryService implements HarnessConversationQueryService {

    private static final String ARTIFACT_COLUMNS = "artifact_id, artifact_type, version, run_id, "
            + "stage, attempt_number, content_type, size_bytes, sha256, classification, "
            + "created_by, created_at";

    private final JdbcTemplate jdbc;
    private final ArtifactStore artifactStore;

    public SqliteHarnessConversationQueryService(JdbcTemplate jdbc, ArtifactStore artifactStore) {
        this.jdbc = jdbc;
        this.artifactStore = artifactStore;
    }

    @Override
    @Transactional(readOnly = true)
    public List<HarnessConversationMessageView> list(String runId) {
        requireRun(runId);
        List<HarnessConversationMessageView> messages = new ArrayList<HarnessConversationMessageView>();
        messages.addAll(userMessages(runId));
        messages.addAll(assistantMessages(runId));
        messages.sort(Comparator.comparingLong(HarnessConversationMessageView::getCreatedAt)
                .thenComparing(HarnessConversationMessageView::getRole)
                .thenComparing(HarnessConversationMessageView::getMessageId));
        return Collections.unmodifiableList(messages);
    }

    private void requireRun(String runId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM harness_run WHERE id=?", Integer.class, runId);
        if (count == null || count.intValue() == 0) {
            throw new HarnessRunNotFoundException(runId);
        }
    }

    private List<HarnessConversationMessageView> userMessages(String runId) {
        return jdbc.query("SELECT sequence, stage, detail, occurred_at FROM harness_event "
                        + "WHERE run_id=? AND event_type='STAGE_CONVERSATION_MESSAGE' "
                        + "ORDER BY sequence",
                (rs, rowNumber) -> {
                    StageConversationMessage message = StageConversationMessage.decode(
                            rs.getString("detail"));
                    return new HarnessConversationMessageView(
                            "event-" + rs.getLong("sequence"), "USER",
                            rs.getString("stage"), message.getAttemptNumber(),
                            message.getContent(), "text/plain", null,
                            rs.getLong("occurred_at"));
                }, runId);
    }

    private List<HarnessConversationMessageView> assistantMessages(String runId) {
        List<ArtifactDescriptor> artifacts = jdbc.query(
                "SELECT " + ARTIFACT_COLUMNS + " FROM harness_artifact "
                        + "WHERE run_id=? AND created_by='harness-runtime' "
                        + "ORDER BY created_at, version", this::readArtifact, runId);
        Map<String, ArtifactDescriptor> primaryByAttempt =
                new LinkedHashMap<String, ArtifactDescriptor>();
        for (ArtifactDescriptor artifact : artifacts) {
            if (artifact.getArtifactType()
                    == StageConversationPolicy.primaryArtifact(artifact.getStage())) {
                primaryByAttempt.put(artifact.getStage().name() + ':' + artifact.getAttempt(), artifact);
            }
        }
        List<HarnessConversationMessageView> messages =
                new ArrayList<HarnessConversationMessageView>();
        for (ArtifactDescriptor artifact : primaryByAttempt.values()) {
            messages.add(new HarnessConversationMessageView(
                    "artifact-" + artifact.getArtifactId() + '-' + artifact.getVersion(),
                    "ASSISTANT", artifact.getStage().name(), artifact.getAttempt(),
                    new String(artifactStore.read(artifact).copyBytes(), StandardCharsets.UTF_8),
                    artifact.getContentType(), artifact.getArtifactType().name(),
                    artifact.getCreatedAt().toEpochMilli()));
        }
        return messages;
    }

    private ArtifactDescriptor readArtifact(ResultSet rs, int rowNumber) throws SQLException {
        return new ArtifactDescriptor(rs.getString("artifact_id"),
                ArtifactType.valueOf(rs.getString("artifact_type")), rs.getInt("version"),
                rs.getString("run_id"), HarnessStage.valueOf(rs.getString("stage")),
                rs.getInt("attempt_number"), rs.getString("content_type"),
                rs.getLong("size_bytes"), rs.getString("sha256"),
                ArtifactClassification.valueOf(rs.getString("classification")),
                rs.getString("created_by"), Instant.ofEpochMilli(rs.getLong("created_at")),
                Collections.<ArtifactReference>emptyList());
    }
}
