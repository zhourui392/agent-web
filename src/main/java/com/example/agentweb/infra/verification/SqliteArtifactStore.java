package com.example.agentweb.infra.verification;

import com.example.agentweb.adapter.verification.CollectedArtifact;
import com.example.agentweb.app.verification.ArtifactStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

/**
 * requirement_artifact 表 SQLite 实现:验证工件落库/回读。
 * 内容超限工件 content 为 null、file_path 指向平台侧文件(由采集器决定,本类只做存取)。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class SqliteArtifactStore implements ArtifactStore {

    private final JdbcTemplate jdbc;

    public SqliteArtifactStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 逐条落库验证工件。
     *
     * @param requirementId 需求 ID
     * @param artifacts     工件列表;null/空列表直接返回
     * @param createdAt     采集时间(落库为 epoch millis)
     */
    @Override
    public void saveAll(String requirementId, List<CollectedArtifact> artifacts, Instant createdAt) {
        if (artifacts == null || artifacts.isEmpty()) {
            return;
        }
        for (CollectedArtifact artifact : artifacts) {
            jdbc.update("INSERT INTO requirement_artifact (requirement_id, kind, content, file_path, "
                            + "created_at) VALUES (?,?,?,?,?)",
                    requirementId,
                    artifact.getKind(),
                    artifact.getContent(),
                    artifact.getFilePath(),
                    createdAt.toEpochMilli());
        }
    }

    /**
     * 按需求 ID 回读工件,按落库顺序(id 升序)返回。
     *
     * @param requirementId 需求 ID
     * @return 工件列表;无记录返回空列表
     */
    @Override
    public List<CollectedArtifact> findByRequirementId(String requirementId) {
        return jdbc.query("SELECT kind, content, file_path FROM requirement_artifact "
                        + "WHERE requirement_id=? ORDER BY id",
                (rs, rowNum) -> new CollectedArtifact(
                        rs.getString("kind"),
                        rs.getString("content"),
                        rs.getString("file_path")),
                requirementId);
    }
}
