package com.example.agentweb.app.delivery;

import com.example.agentweb.adapter.delivery.CreateMrCommand;
import com.example.agentweb.adapter.delivery.PushBranchCommand;
import com.example.agentweb.adapter.delivery.ScmCredential;
import com.example.agentweb.adapter.delivery.ScmCredentialStore;
import com.example.agentweb.adapter.delivery.ScmGateway;
import com.example.agentweb.app.requirement.RequirementProperties;
import com.example.agentweb.domain.delivery.MergeRequestRef;
import com.example.agentweb.domain.requirement.Requirement;
import com.example.agentweb.domain.requirement.RequirementEvent;
import com.example.agentweb.domain.requirement.RequirementRepository;
import com.example.agentweb.domain.requirement.RequirementSource;
import com.example.agentweb.domain.workspace.RequirementWorkspace;
import com.example.agentweb.domain.workspace.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 交付编排：凭证链两级回落、push→MR 顺序、trailer 组装两态、403 引导、落库与审计事件。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class DeliveryAppServiceTest {

    private static final String REQ_ID = "R2607040001";
    private static final String OWNER = "V33215020";

    private RequirementRepository requirementRepository;
    private WorkspaceRepository workspaceRepository;
    private ScmCredentialStore credentialStore;
    private ScmGateway scmGateway;
    private MergeRequestStore mergeRequestStore;
    private DeliveryAppService service;
    private Requirement requirement;

    @BeforeEach
    public void setUp() {
        requirementRepository = mock(RequirementRepository.class);
        workspaceRepository = mock(WorkspaceRepository.class);
        credentialStore = mock(ScmCredentialStore.class);
        scmGateway = mock(ScmGateway.class);
        mergeRequestStore = mock(MergeRequestStore.class);
        service = new DeliveryAppService(requirementRepository, workspaceRepository,
                credentialStore, scmGateway, mergeRequestStore, new RequirementProperties());

        requirement = Requirement.create(RequirementSource.BOARD, "结算对账优化", "d", OWNER);
        requirement.pullEvents();
        when(requirementRepository.findById(REQ_ID)).thenReturn(requirement);
        RequirementWorkspace workspace = RequirementWorkspace.create(REQ_ID,
                "http://git/group/repo.git", "D:/ws/m", "D:/ws/wt/" + REQ_ID, 72);
        workspace.markReady();
        when(workspaceRepository.findByRequirementId(REQ_ID)).thenReturn(workspace);
        when(scmGateway.createDraftMergeRequest(any())).thenReturn(
                new MergeRequestRef(7, "http://git/mr/7", true, null));
    }

    @Test
    public void deliverDraft_personal_credential_should_push_then_create_mr() {
        when(credentialStore.findPersonal(OWNER)).thenReturn(
                Optional.of(new ScmCredential("me", "tok", false)));

        MergeRequestRef mr = service.deliverDraft(REQ_ID, OWNER);

        assertEquals(7, mr.getMrIid());
        InOrder inOrder = inOrder(scmGateway, mergeRequestStore, requirementRepository);
        ArgumentCaptor<PushBranchCommand> pushCaptor = ArgumentCaptor.forClass(PushBranchCommand.class);
        inOrder.verify(scmGateway).pushBranch(pushCaptor.capture());
        inOrder.verify(scmGateway).createDraftMergeRequest(any(CreateMrCommand.class));
        inOrder.verify(mergeRequestStore).upsert(REQ_ID, mr);
        inOrder.verify(requirementRepository).update(requirement);

        // 个人凭证: trailer 只有 session 回链,无 Operated-By
        List<String> trailers = pushCaptor.getValue().getCommitTrailers();
        assertEquals(1, trailers.size());
        assertTrue(trailers.get(0).startsWith("Agent-Web-Session: requirement:" + REQ_ID));

        List<RequirementEvent> events = requirement.pullEvents();
        assertEquals(RequirementEvent.TYPE_MR_DRAFTED, events.get(0).getEventType());
    }

    @Test
    public void deliverDraft_default_account_should_append_operated_by() {
        when(credentialStore.findPersonal(OWNER)).thenReturn(Optional.empty());
        when(credentialStore.findDefaultAccount()).thenReturn(
                Optional.of(new ScmCredential("bot", "tok", true)));

        service.deliverDraft(REQ_ID, OWNER);

        ArgumentCaptor<PushBranchCommand> pushCaptor = ArgumentCaptor.forClass(PushBranchCommand.class);
        verify(scmGateway).pushBranch(pushCaptor.capture());
        assertEquals("Operated-By: " + OWNER, pushCaptor.getValue().getCommitTrailers().get(1));
    }

    @Test
    public void deliverDraft_should_reject_without_any_credential() {
        when(credentialStore.findPersonal(OWNER)).thenReturn(Optional.empty());
        when(credentialStore.findDefaultAccount()).thenReturn(Optional.empty());

        assertThrows(CredentialInsufficientException.class,
                () -> service.deliverDraft(REQ_ID, OWNER));
        verify(scmGateway, never()).pushBranch(any());
    }

    @Test
    public void deliverDraft_should_map_403_to_credential_guidance() {
        when(credentialStore.findPersonal(OWNER)).thenReturn(
                Optional.of(new ScmCredential("me", "tok", false)));
        when(scmGateway.createDraftMergeRequest(any())).thenThrow(
                HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden",
                        new HttpHeaders(), new byte[0], StandardCharsets.UTF_8));

        assertThrows(CredentialInsufficientException.class,
                () -> service.deliverDraft(REQ_ID, OWNER));
    }

    @Test
    public void deliverDraft_should_require_workspace() {
        when(workspaceRepository.findByRequirementId(REQ_ID)).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> service.deliverDraft(REQ_ID, OWNER));
        verify(credentialStore, never()).findPersonal(anyString());
    }

    @Test
    public void deliverDraft_should_force_draft_title() {
        when(credentialStore.findPersonal(OWNER)).thenReturn(
                Optional.of(new ScmCredential("me", "tok", false)));

        service.deliverDraft(REQ_ID, OWNER);

        ArgumentCaptor<CreateMrCommand> mrCaptor = ArgumentCaptor.forClass(CreateMrCommand.class);
        verify(scmGateway).createDraftMergeRequest(mrCaptor.capture());
        assertTrue(mrCaptor.getValue().getTitle().startsWith("Draft: "));
        assertEquals("master", mrCaptor.getValue().getTargetBranch());
    }
}
