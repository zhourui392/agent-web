package com.example.agentweb.infra.verification;

import com.example.agentweb.domain.verification.VerificationOutcome;
import com.example.agentweb.domain.verification.VerificationRound;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * verification_round 表读写轻集成：追加落库、按需求升序取回、自增 id 回填可读。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class SqliteVerificationRoundRepoTest {

    @TempDir
    Path tempDir;

    private SqliteVerificationRoundRepo repo;

    @BeforeEach
    public void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("round-test.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE verification_round (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "requirement_id TEXT NOT NULL, round INTEGER NOT NULL, deploy_ref TEXT, "
                + "verdict TEXT NOT NULL, failed_count INTEGER NOT NULL DEFAULT 0, "
                + "evidence_ref TEXT, created_at INTEGER NOT NULL)");
        repo = new SqliteVerificationRoundRepo(jdbc);
    }

    @Test
    public void save_and_find_should_return_rounds_in_ascending_order() {
        repo.save(VerificationRound.record("R1", 1, VerificationOutcome.BLOCKED, 2,
                "requirement_artifact:R1"));
        repo.save(VerificationRound.record("R1", 2, VerificationOutcome.VERIFIED, 0, ""));
        repo.save(VerificationRound.record("R-other", 1, VerificationOutcome.DEPLOY_FAILED, 0, ""));

        List<VerificationRound> rounds = repo.findByRequirementId("R1");

        assertEquals(2, rounds.size());
        assertEquals(1, rounds.get(0).getRound());
        assertEquals(VerificationOutcome.BLOCKED, rounds.get(0).getVerdict());
        assertEquals(2, rounds.get(0).getFailedCount());
        assertEquals("requirement_artifact:R1", rounds.get(0).getEvidenceRef());
        assertEquals(VerificationOutcome.VERIFIED, rounds.get(1).getVerdict());
        assertTrue(rounds.get(1).getId() > rounds.get(0).getId());
    }
}
