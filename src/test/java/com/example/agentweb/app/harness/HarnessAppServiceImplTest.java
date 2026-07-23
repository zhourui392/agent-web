package com.example.agentweb.app.harness;

import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.auth.UserContext;
import com.example.agentweb.domain.harness.ArtifactClassification;
import com.example.agentweb.domain.harness.ArtifactContent;
import com.example.agentweb.domain.harness.ArtifactReference;
import com.example.agentweb.domain.harness.ArtifactStore;
import com.example.agentweb.domain.harness.ArtifactType;
import com.example.agentweb.domain.harness.DuplicateHarnessRunException;
import com.example.agentweb.domain.harness.HarnessRun;
import com.example.agentweb.domain.harness.HarnessRunNotFoundException;
import com.example.agentweb.domain.harness.HarnessRunRepository;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.StageContract;
import com.example.agentweb.domain.worktree.WorkspacePathPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Harness Application 层只验证 Repository、Store、路径端口和聚合的编排。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@ExtendWith(MockitoExtension.class)
class HarnessAppServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");

    @Mock
    private HarnessRunRepository repository;
    @Mock
    private ArtifactStore artifactStore;
    @Mock
    private WorkspacePathPolicy workspacePathPolicy;

    private HarnessAppServiceImpl service;

    @BeforeEach
    void setUp() {
        UserContext userContext = () -> Optional.of(new LoginUser("admin", "Admin", null));
        CurrentUserProvider currentUserProvider = new CurrentUserProvider(userContext);
        HarnessIdGenerator idGenerator = new HarnessIdGenerator() {
            private int value;

            @Override
            public String nextId() {
                value++;
                return "generated-" + value;
            }
        };
        service = new HarnessAppServiceImpl(repository, artifactStore, workspacePathPolicy,
                currentUserProvider, idGenerator, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void create_should_validate_workspace_then_persist_new_aggregate() {
        when(workspacePathPolicy.requireExistingDirectory("/requested"))
                .thenReturn("/real/workspace");
        when(repository.findByCreatorAndIdempotencyKey("admin", "create-key"))
                .thenReturn(Optional.<HarnessRun>empty());

        HarnessMutationResult result = service.create(new CreateHarnessRunCommand(
                "M1", "/requested", "CODEX", "local", "harness@1.0.0", "create-key"));

        assertEquals("generated-1", result.getRunId());
        assertEquals("DRAFT", result.getStatus());
        assertTrue(!result.isDuplicated());
        verify(workspacePathPolicy).requireExistingDirectory("/requested");
        verify(repository).add(any(HarnessRun.class));
    }

    @Test
    void create_duplicate_should_return_same_run_but_reject_different_request() {
        HarnessRun existing = newRun();
        when(repository.findByCreatorAndIdempotencyKey("admin", "create-key"))
                .thenReturn(Optional.of(existing));
        when(workspacePathPolicy.requireExistingDirectory("/requested"))
                .thenReturn("/workspace");

        HarnessMutationResult duplicate = service.create(new CreateHarnessRunCommand(
                "M1", "/requested", "CODEX", "local", "harness@1.0.0", "create-key"));
        assertTrue(duplicate.isDuplicated());
        verify(repository, never()).add(any(HarnessRun.class));

        assertThrows(DuplicateHarnessRunException.class, () -> service.create(
                new CreateHarnessRunCommand("different", "/requested", "CODEX", "local",
                        "harness@1.0.0", "create-key")));
    }

    @Test
    void start_should_delegate_state_rule_to_aggregate_and_persist_only_changed_command() {
        HarnessRun run = newRun();
        when(repository.findById("run-1")).thenReturn(Optional.of(run));

        service.startStage("run-1", HarnessStage.ANALYSIS, "start-key");
        service.startStage("run-1", HarnessStage.ANALYSIS, "start-key");

        assertEquals(1, run.stage(HarnessStage.ANALYSIS).getAttempts().size());
        verify(repository, times(1)).update(run);
    }

    @Test
    void register_artifact_should_store_body_before_persisting_metadata() {
        HarnessRun run = newRun();
        run.startStage(HarnessStage.ANALYSIS, "start", NOW);
        when(repository.findById("run-1")).thenReturn(Optional.of(run));
        ArtifactContent content = ArtifactContent.from("requirement".getBytes(StandardCharsets.UTF_8));

        service.registerArtifact(new RegisterHarnessArtifactCommand(
                "run-1", HarnessStage.ANALYSIS, ArtifactType.REQUIREMENT,
                content.copyBytes(), "text/markdown", ArtifactClassification.INTERNAL,
                Collections.<ArtifactReference>emptyList()));

        InOrder order = inOrder(artifactStore, repository);
        order.verify(artifactStore).store(any(), any());
        order.verify(repository).update(run);
        assertEquals(1, run.getArtifacts().size());
    }

    @Test
    void missing_run_should_map_to_domain_not_found_without_side_effect() {
        when(repository.findById("missing")).thenReturn(Optional.<HarnessRun>empty());

        assertThrows(HarnessRunNotFoundException.class,
                () -> service.startStage("missing", HarnessStage.ANALYSIS, "start-key"));
        verify(repository, never()).update(any(HarnessRun.class));
    }

    private HarnessRun newRun() {
        return HarnessRun.create("run-1", "M1", "/workspace", "CODEX", "local",
                "harness@1.0.0", "admin", "create-key", StageContract.mvpDefaults(), NOW);
    }
}
