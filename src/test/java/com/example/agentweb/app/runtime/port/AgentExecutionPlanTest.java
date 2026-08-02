package com.example.agentweb.app.runtime.port;

import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.CanonicalHashing;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author alex
 * @since 2026-08-01
 */
class AgentExecutionPlanTest {

    @Test
    void createsCompletePlanWithoutNullableOptions() {
        ResolvedCapabilityBinding binding = capabilityBinding();
        AgentExecutionPlan plan = new AgentExecutionPlan(
                new ExecutionIdentity("exec-1", "owner-1", "workbench:wb-1:implementation:1"),
                new RuntimeSelection(AgentType.CODEX,
                        RuntimeVersionPolicy.exact("codex-cli@1.2.3")),
                new PromptPayload("implement the approved design",
                        CanonicalHashing.sha256("implement the approved design"),
                        HistoryDelivery.PROMPT_PREFIX),
                new WorkspaceLayout("/workspace/service-a",
                        Arrays.asList("/workspace/service-a", "/workspace/service-b"),
                        Collections.singletonList("/workspace/service-a"),
                        SandboxMode.WORKSPACE_WRITE),
                binding,
                new RuntimeLimits(Duration.ofMinutes(20), 8_388_608L));

        assertEquals("exec-1", plan.getExecutionIdentity().getExecutionId());
        assertEquals(AgentType.CODEX, plan.getRuntimeSelection().getAgentType());
        assertEquals("implement the approved design", plan.getPromptPayload().getFinalPrompt());
        assertEquals("/workspace/service-a",
                plan.getWorkspaceLayout().getPrimaryRepositoryRoot());
        assertSame(binding, plan.getCapabilityBinding());
        assertEquals(Duration.ofMinutes(20), plan.getRuntimeLimits().getTimeout());
    }

    @Test
    void defensiveCopiesAllPlanCollections() {
        ArrayList<String> readable = new ArrayList<String>(Arrays.asList(
                "/workspace/service-a", "/workspace/service-b"));
        ArrayList<String> writable = new ArrayList<String>(Collections.singletonList(
                "/workspace/service-a"));

        WorkspaceLayout layout = new WorkspaceLayout("/workspace/service-a", readable,
                writable, SandboxMode.WORKSPACE_WRITE);
        RuntimeLimits limits = new RuntimeLimits(Duration.ofSeconds(30), 1024);
        readable.clear();
        writable.clear();

        assertEquals(2, layout.getReadableRoots().size());
        assertEquals(1, layout.getWritableRoots().size());
        assertThrows(UnsupportedOperationException.class,
                () -> layout.getReadableRoots().add("/workspace/service-c"));
        assertThrows(UnsupportedOperationException.class,
                () -> layout.getWritableRoots().clear());
    }

    @Test
    void attachmentsMustRemainUniqueBoundedAndInsideReadableRoots() {
        RuntimeAttachmentExpectation expected = attachment(
                "/workspace/service-a", "docs/design.md");
        RuntimeAttachmentExpectation duplicate = attachment(
                "/workspace/service-a", "docs/design.md");
        RuntimeAttachmentExpectation unreadable = attachment(
                "/workspace/service-b", "docs/design.md");

        assertThrows(IllegalArgumentException.class,
                () -> planWithAttachments(Collections.singletonList(unreadable)));
        assertThrows(IllegalArgumentException.class,
                () -> planWithAttachments(Arrays.asList(expected, duplicate)));
        assertThrows(IllegalArgumentException.class,
                () -> planWithAttachments(Collections.nCopies(9, expected)));
    }

    @Test
    void uploadedAttachmentsDoNotPretendToBeReadableRepositoryRoots() {
        RuntimeAttachmentExpectation uploaded = uploadedAttachment("attachment-1");

        AgentExecutionPlan plan = planWithAttachments(
                Collections.singletonList(uploaded));

        assertSame(uploaded, plan.getAttachmentExpectations().get(0));
        assertThrows(IllegalArgumentException.class,
                () -> planWithAttachments(Arrays.asList(
                        uploaded, uploadedAttachment("attachment-1"))));
    }

    @Test
    void attachmentsAreDefensivelyCopiedAndImmutable() {
        ArrayList<RuntimeAttachmentExpectation> attachments =
                new ArrayList<RuntimeAttachmentExpectation>();
        attachments.add(attachment(
                "/workspace/service-a", "docs/design.md"));

        AgentExecutionPlan plan = planWithAttachments(attachments);
        attachments.clear();

        assertEquals(1, plan.getAttachmentExpectations().size());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.getAttachmentExpectations().clear());
    }

    @Test
    void readOnlySandboxRejectsWritableRoots() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorkspaceLayout("/workspace/service-a",
                        Collections.singletonList("/workspace/service-a"),
                        Collections.singletonList("/workspace/service-a"),
                        SandboxMode.READ_ONLY));

        WorkspaceLayout layout = new WorkspaceLayout("/workspace/service-a",
                Collections.singletonList("/workspace/service-a"),
                Collections.<String>emptyList(), SandboxMode.READ_ONLY);

        assertTrue(layout.getWritableRoots().isEmpty());
    }

    @Test
    void writeSandboxRequiresWritableRootsToBeReadable() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorkspaceLayout("/workspace/service-a",
                        Collections.singletonList("/workspace/service-a"),
                        Collections.singletonList("/workspace/service-b"),
                        SandboxMode.WORKSPACE_WRITE));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkspaceLayout("/workspace/service-a",
                        Collections.singletonList("/workspace/service-a"),
                        Collections.<String>emptyList(), SandboxMode.WORKSPACE_WRITE));
    }

    @Test
    void workspaceRequiresNormalizedAbsolutePrimaryAndReadableRoots() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorkspaceLayout("workspace/service-a",
                        Collections.singletonList("/workspace/service-a"),
                        Collections.<String>emptyList(), SandboxMode.READ_ONLY));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkspaceLayout("/workspace/service-a",
                        Collections.singletonList("/workspace/service-b"),
                        Collections.<String>emptyList(), SandboxMode.READ_ONLY));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkspaceLayout("/workspace/service-a/../service-a",
                        Collections.singletonList("/workspace/service-a"),
                        Collections.<String>emptyList(), SandboxMode.READ_ONLY));
    }

    @Test
    void promptPayloadRejectsHashThatDoesNotMatchFinalPrompt() {
        assertThrows(IllegalArgumentException.class,
                () -> new PromptPayload("actual prompt", CanonicalHashing.sha256("other"),
                        HistoryDelivery.PROVIDER_RESUME));
        assertThrows(IllegalArgumentException.class,
                () -> new PromptPayload("actual prompt", CanonicalHashing.sha256("actual prompt"),
                        null));
    }

    @Test
    void runtimeLimitsRequirePositiveBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeLimits(Duration.ZERO, 1024));
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeLimits(Duration.ofSeconds(1), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeLimits(null, 1024));
    }

    @Test
    void executionGatewayExposesStableHandleStopAndTechnicalObservation() {
        RuntimeHandle handle = new RuntimeHandle("exec-1", "runtime-handle-1");
        RuntimeObservation running = RuntimeObservation.running(handle, 128L);
        RecordingGateway gateway = new RecordingGateway(handle, running);
        RuntimeEventSink sink = event -> { };

        RuntimeHandle started = gateway.start(plan(), sink);
        gateway.requestStop(started);
        RuntimeObservation observed = gateway.observe(started);

        assertSame(handle, started);
        assertSame(running, observed);
        assertSame(handle, gateway.stopRequestedFor);
        assertEquals(RuntimeState.RUNNING, observed.getState());
        assertEquals(128L, observed.getObservedOutputBytes());
        assertFalse(observed.termination().isPresent());
    }

    @Test
    void terminalObservationCarriesProviderNeutralTechnicalFacts() {
        RuntimeHandle handle = new RuntimeHandle("exec-1", "runtime-handle-1");

        RuntimeObservation observation = RuntimeObservation.terminated(handle, 143,
                RuntimeTerminationReason.REQUESTED_STOP, 256L);

        assertEquals(RuntimeState.TERMINATED, observation.getState());
        assertTrue(observation.termination().isPresent());
        assertEquals(143, observation.termination().get().getExitCode());
        assertEquals(RuntimeTerminationReason.REQUESTED_STOP,
                observation.termination().get().getReason());
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeObservation.running(handle, -1L));
    }

    @Test
    void runtimeEventsAreSequencedBoundedAndProviderNeutral() {
        RuntimeEvent event = new RuntimeEvent("exec-1", 1L, RuntimeEventType.OUTPUT,
                "safe normalized output");

        assertEquals("exec-1", event.getExecutionId());
        assertEquals(1L, event.getSequence());
        assertEquals(RuntimeEventType.OUTPUT, event.getType());
        assertEquals("safe normalized output", event.getSafePayload());
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeEvent("exec-1", 0L, RuntimeEventType.OUTPUT, "output"));
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeEvent("exec-1", 1L, RuntimeEventType.OUTPUT,
                        String.join("", Collections.nCopies(65_537, "x"))));
    }

    private static AgentExecutionPlan plan() {
        String prompt = "prompt";
        return new AgentExecutionPlan(
                new ExecutionIdentity("exec-1", "owner-1", "chat:conversation-1:run-1"),
                new RuntimeSelection(AgentType.CODEX, RuntimeVersionPolicy.configured()),
                new PromptPayload(prompt, CanonicalHashing.sha256(prompt),
                        HistoryDelivery.PROVIDER_RESUME),
                new WorkspaceLayout("/workspace/service-a",
                        Collections.singletonList("/workspace/service-a"),
                        Collections.<String>emptyList(), SandboxMode.READ_ONLY),
                capabilityBinding(),
                new RuntimeLimits(Duration.ofSeconds(30), 1024));
    }

    private static AgentExecutionPlan planWithAttachments(
            List<RuntimeAttachmentExpectation> attachments) {
        AgentExecutionPlan base = plan();
        return new AgentExecutionPlan(
                base.getExecutionIdentity(), base.getRuntimeSelection(),
                base.getPromptPayload(), base.getWorkspaceLayout(),
                base.getCapabilityBinding(), base.getRuntimeLimits(),
                attachments);
    }

    private static RuntimeAttachmentExpectation attachment(
            String repositoryRoot, String relativePath) {
        return new RuntimeAttachmentExpectation(
                "repository", repositoryRoot, relativePath,
                CanonicalHashing.sha256("approved"), 8L);
    }

    private static RuntimeAttachmentExpectation uploadedAttachment(
            String attachmentId) {
        return RuntimeAttachmentExpectation.uploadedConversation(
                attachmentId,
                CanonicalHashing.sha256("storage-" + attachmentId),
                "attachment-1234567890abcdefabcd.md",
                CanonicalHashing.sha256("approved"), 8L);
    }

    private static ResolvedCapabilityBinding capabilityBinding() {
        return ResolvedCapabilityBinding.resolve("policy@1", "profile", "1",
                CanonicalHashing.sha256("profile"), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                "codex:*");
    }

    private static final class RecordingGateway implements AgentExecutionGateway {
        private final RuntimeHandle handle;
        private final RuntimeObservation observation;
        private RuntimeHandle stopRequestedFor;

        private RecordingGateway(RuntimeHandle handle, RuntimeObservation observation) {
            this.handle = handle;
            this.observation = observation;
        }

        @Override
        public RuntimeHandle start(AgentExecutionPlan plan, RuntimeEventSink sink) {
            return handle;
        }

        @Override
        public void requestStop(RuntimeHandle handle) {
            stopRequestedFor = handle;
        }

        @Override
        public RuntimeObservation observe(RuntimeHandle handle) {
            return observation;
        }
    }
}
