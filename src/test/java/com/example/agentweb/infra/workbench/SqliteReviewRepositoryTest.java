package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.workbench.ReviewModifyConfirmation;
import com.example.agentweb.domain.workbench.ReviewOpinion;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;

import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.NOW;
import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Review Opinion current 与 exact Confirmation 的真实 SQLite 查询测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class SqliteReviewRepositoryTest {

    private static final String CONTENT_A = "提取 Review 策略对象";
    private static final String CONTENT_B = "拆分 Review 策略并运行测试";

    @TempDir
    Path tempDir;

    private Workbench workbench;
    private SqliteReviewOpinionRepository opinionRepository;
    private SqliteReviewModifyConfirmationRepository confirmationRepository;

    @BeforeEach
    void setUp() throws Exception {
        JdbcTemplate jdbc = WorkbenchPersistenceFixtures.initializedJdbc(
                tempDir.resolve("review.db"));
        WorkbenchPersistenceFixtures.WorkspaceFixture workspace =
                WorkbenchPersistenceFixtures.persistWorkspace(
                        jdbc, tempDir, "review-snapshot");
        workbench = WorkbenchPersistenceFixtures.newWorkbench(
                workspace, "workbench-review");
        new SqliteWorkbenchRepository(jdbc).add(workbench);
        opinionRepository = new SqliteReviewOpinionRepository(jdbc);
        confirmationRepository =
                new SqliteReviewModifyConfirmationRepository(jdbc);
    }

    @Test
    void opinionShouldFindLatestAndTranslateDuplicateVersionToConflict() {
        ReviewOpinion first = ReviewOpinion.start(
                workbench.getId(), 0L, CONTENT_A,
                OWNER, NOW.plusSeconds(1));
        ReviewOpinion second = first.revise(
                1L, CONTENT_B,
                OWNER, NOW.plusSeconds(2));
        opinionRepository.add(first);
        opinionRepository.add(second);

        ReviewOpinion latest = opinionRepository
                .findLatest(workbench.getId())
                .orElseThrow(AssertionError::new);

        assertEquals(2L, latest.getVersion());
        assertEquals(CONTENT_B, latest.getContent());
        assertEquals(second.getContentHash(),
                latest.getContentHash());
        WorkbenchDomainException conflict = assertThrows(
                WorkbenchDomainException.class,
                () -> opinionRepository.add(second));
        assertEquals(WorkbenchErrorCode.VERSION_CONFLICT,
                conflict.getCode());
    }

    @Test
    void confirmationRecoveryShouldUseExactCurrentOpinionAndLatestTime() {
        ReviewOpinion first = ReviewOpinion.start(
                workbench.getId(), 0L, CONTENT_A,
                OWNER, NOW.plusSeconds(1));
        ReviewOpinion second = first.revise(
                1L, CONTENT_B,
                OWNER, NOW.plusSeconds(2));
        opinionRepository.add(first);
        opinionRepository.add(second);
        confirmationRepository.add(ReviewModifyConfirmation.confirm(
                "confirmation-old", first, OWNER,
                NOW.plusSeconds(3)));
        confirmationRepository.add(ReviewModifyConfirmation.confirm(
                "confirmation-current-1", second, OWNER,
                NOW.plusSeconds(4)));
        confirmationRepository.add(ReviewModifyConfirmation.confirm(
                "confirmation-current-2", second, OWNER,
                NOW.plusSeconds(5)));

        ReviewModifyConfirmation latest = confirmationRepository.findLatest(
                        workbench.getId(), 2L, second.getContentHash())
                .orElseThrow(AssertionError::new);

        assertEquals("confirmation-current-2",
                latest.getConfirmationId());
        assertEquals(CONTENT_B, latest.getOpinion().getContent());
        assertFalse(confirmationRepository.findLatest(
                workbench.getId(), 2L,
                first.getContentHash()).isPresent());
    }
}
