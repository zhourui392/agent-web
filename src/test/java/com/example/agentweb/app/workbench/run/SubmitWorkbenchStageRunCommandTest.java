package com.example.agentweb.app.workbench.run;

import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchRunAttachmentReference;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 动态 Stage Run 提交命令的身份、Command 解析和幂等契约测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class SubmitWorkbenchStageRunCommandTest {

    private static final String CONTENT_HASH = String.join(
            "", Collections.nCopies(64, "a"));

    @Test
    void should_NormalizeStageIdentityAndParseExplicitCommandInvocation() {
        // Given
        WorkbenchRunAttachmentReference attachment =
                WorkbenchRunAttachmentReference.of(
                        "agent-web", "docs/design.md", CONTENT_HASH);

        // When
        SubmitWorkbenchStageRunCommand command =
                new SubmitWorkbenchStageRunCommand(
                        WorkbenchId.of("workbench-1"), "stage-design", 7L,
                        "  stage-submit-key  ",
                        "  /architecture-review module A  ",
                        RunMode.DISCUSS_READ_ONLY,
                        Collections.singletonList(attachment));

        // Then
        assertEquals("stage-design", command.getStageInstanceIdentifier());
        assertEquals("stage-submit-key", command.getIdempotencyKey());
        assertEquals("/architecture-review module A", command.getMessage());
        assertEquals("architecture-review",
                command.getCommandInvocation().getIdentifier());
        assertEquals("module A", command.getCommandInvocation().getArguments());
        assertEquals(1, command.getAttachments().size());
    }

    @Test
    void should_KeepPlainMessageWithoutCommandInvocation() {
        // Given / When
        SubmitWorkbenchStageRunCommand command = command(
                "stage-design", "Discuss the design.",
                RunMode.DISCUSS_READ_ONLY);

        // Then
        assertNull(command.getCommandInvocation());
    }

    @Test
    void should_HashEveryAcceptedBusinessFactButIgnoreRetryMetadata() {
        // Given
        SubmitWorkbenchStageRunCommand base = command(
                "stage-design", "/architecture-review module A",
                RunMode.DISCUSS_READ_ONLY);
        SubmitWorkbenchStageRunCommand retry =
                new SubmitWorkbenchStageRunCommand(
                        WorkbenchId.of("workbench-1"), "stage-design", 99L,
                        "another-key", "/architecture-review module A",
                        RunMode.DISCUSS_READ_ONLY, Collections.emptyList());

        // When / Then
        assertEquals(base.getRequestHash(), retry.getRequestHash());
        assertNotEquals(base.getRequestHash(), command(
                "stage-implementation", "/architecture-review module A",
                RunMode.DISCUSS_READ_ONLY).getRequestHash());
        assertNotEquals(base.getRequestHash(), command(
                "stage-design", "/architecture-review module B",
                RunMode.DISCUSS_READ_ONLY).getRequestHash());
        assertNotEquals(base.getRequestHash(), command(
                "stage-design", "/architecture-review module A",
                RunMode.MODIFY_WORKSPACE).getRequestHash());
    }

    @Test
    void should_RejectInvalidStageOrCommandIdentity() {
        // Given / When / Then
        assertThrows(IllegalArgumentException.class,
                () -> command("../stage", "message",
                        RunMode.DISCUSS_READ_ONLY));
        assertThrows(IllegalArgumentException.class,
                () -> command("stage-design", "/BadCommand arguments",
                        RunMode.DISCUSS_READ_ONLY));
        assertThrows(IllegalArgumentException.class,
                () -> new SubmitWorkbenchStageRunCommand(
                        WorkbenchId.of("workbench-1"), "stage-design", -1L,
                        "key", "message", RunMode.DISCUSS_READ_ONLY,
                        Collections.emptyList()));
    }

    private SubmitWorkbenchStageRunCommand command(
            String stageInstanceIdentifier, String message,
            RunMode runMode) {
        return new SubmitWorkbenchStageRunCommand(
                WorkbenchId.of("workbench-1"), stageInstanceIdentifier,
                7L, "stage-submit-key", message, runMode,
                Collections.emptyList());
    }
}
