package com.example.agentweb.infra.verification;

import com.example.agentweb.domain.verification.VerificationOutcome;
import com.example.agentweb.domain.verification.VerificationRound;
import com.example.agentweb.domain.verification.VerificationRoundRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;

/**
 * verification_round 表 SQLite 实现（只追加不改写）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class SqliteVerificationRoundRepo implements VerificationRoundRepository {

    private final JdbcTemplate jdbc;

    private final RowMapper<VerificationRound> rowMapper = (rs, rowNum) -> new VerificationRound(
            rs.getLong("id"),
            rs.getString("requirement_id"),
            rs.getInt("round"),
            rs.getString("deploy_ref"),
            VerificationOutcome.valueOf(rs.getString("verdict")),
            rs.getInt("failed_count"),
            rs.getString("evidence_ref"),
            Instant.ofEpochMilli(rs.getLong("created_at")));

    public SqliteVerificationRoundRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(VerificationRound round) {
        jdbc.update("INSERT INTO verification_round (requirement_id, round, deploy_ref, verdict, "
                        + "failed_count, evidence_ref, created_at) VALUES (?,?,?,?,?,?,?)",
                round.getRequirementId(),
                round.getRound(),
                round.getDeployRef(),
                round.getVerdict().name(),
                round.getFailedCount(),
                round.getEvidenceRef(),
                round.getCreatedAt().toEpochMilli());
    }

    @Override
    public List<VerificationRound> findByRequirementId(String requirementId) {
        return jdbc.query("SELECT id, requirement_id, round, deploy_ref, verdict, failed_count, "
                        + "evidence_ref, created_at FROM verification_round WHERE requirement_id=? "
                        + "ORDER BY round ASC", rowMapper, requirementId);
    }
}
