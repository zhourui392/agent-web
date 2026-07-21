package com.example.agentweb.app.delivery;

import com.example.agentweb.adapter.delivery.ScmGateway;
import com.example.agentweb.adapter.delivery.ScmWebhookEvent;
import com.example.agentweb.adapter.delivery.WebhookEnvelope;
import com.example.agentweb.app.requirement.RequirementAppService;
import com.example.agentweb.app.requirement.RequirementIdempotencyStore;
import com.example.agentweb.app.requirement.RequirementProperties;
import com.example.agentweb.domain.requirement.Requirement;
import com.example.agentweb.domain.requirement.RequirementEvent;
import com.example.agentweb.domain.requirement.RequirementRepository;
import com.example.agentweb.domain.requirement.RequirementSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * webhook 事件编排：UUID 去重、MrMerged→T10、失败事件→fix 建议、issue→建需求（owner 回落 + URL 幂等）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class ScmWebhookAppServiceTest {

    private ScmGateway scmGateway;
    private WebhookDedupStore dedupStore;
    private RequirementRepository repository;
    private RequirementAppService appService;
    private RequirementIdempotencyStore idempotencyStore;
    private RequirementProperties properties;
    private com.example.agentweb.adapter.UserDirectory userDirectory;
    private ScmWebhookAppService service;

    @BeforeEach
    public void setUp() {
        scmGateway = mock(ScmGateway.class);
        dedupStore = mock(WebhookDedupStore.class);
        repository = mock(RequirementRepository.class);
        appService = mock(RequirementAppService.class);
        idempotencyStore = mock(RequirementIdempotencyStore.class);
        properties = new RequirementProperties();
        userDirectory = mock(com.example.agentweb.adapter.UserDirectory.class);
        when(userDirectory.containsUser(anyString())).thenReturn(true);
        service = new ScmWebhookAppService(scmGateway, dedupStore, repository,
                appService, idempotencyStore, properties, userDirectory);
        when(dedupStore.tryMarkProcessed(anyString(), any())).thenReturn(true);
    }

    @Test
    public void duplicate_event_uuid_should_skip_parse() {
        when(dedupStore.tryMarkProcessed(eq("uuid-1"), any())).thenReturn(false);

        service.handle("uuid-1", "X", "{}");

        verify(scmGateway, never()).parseWebhook(any());
    }

    @Test
    public void mr_merged_should_mark_delivered_by_branch_mapping() {
        when(scmGateway.parseWebhook(any(WebhookEnvelope.class))).thenReturn(
                new ScmWebhookEvent.MrMerged(7, "req/R2607040001", "reviewer"));

        service.handle("uuid-1", "X", "{}");

        verify(appService).markDelivered(eq("R2607040001"), eq("system:webhook"),
                eq("MR !7 merged by reviewer"));
    }

    @Test
    public void mr_merged_outside_req_namespace_should_be_ignored() {
        when(scmGateway.parseWebhook(any(WebhookEnvelope.class))).thenReturn(
                new ScmWebhookEvent.MrMerged(7, "feature/x", "reviewer"));

        service.handle("uuid-1", "X", "{}");

        verify(appService, never()).markDelivered(anyString(), anyString(), anyString());
    }

    @Test
    public void pipeline_failed_should_record_fix_suggestion_event() {
        Requirement requirement = implementingRequirement();
        when(repository.findById("R2607040001")).thenReturn(requirement);
        when(scmGateway.parseWebhook(any(WebhookEnvelope.class))).thenReturn(
                new ScmWebhookEvent.PipelineFailed("req/R2607040001", "http://ci/1", "failed"));

        service.handle("uuid-1", "X", "{}");

        verify(repository).update(requirement);
        List<RequirementEvent> events = requirement.pullEvents();
        assertEquals(RequirementEvent.TYPE_FIX_SUGGESTED, events.get(0).getEventType());
    }

    @Test
    public void issue_labeled_should_create_requirement_with_owner_fallback() {
        properties.getIntake().setDefaultOwner("V999");
        when(idempotencyStore.findRequirementId("gitlab-issue", "http://git/i/1"))
                .thenReturn(Optional.empty());
        when(appService.createWithRef(eq(RequirementSource.GITLAB_ISSUE), eq("http://git/i/1"),
                eq("标题"), eq("正文"), eq("V999"))).thenReturn("RNEW");
        when(scmGateway.parseWebhook(any(WebhookEnvelope.class))).thenReturn(new ScmWebhookEvent.IssueLabeled(
                "http://git/i/1", "标题", "正文", " ", List.of("agent-dev")));

        service.handle("uuid-1", "X", "{}");

        verify(idempotencyStore).record(eq("gitlab-issue"), eq("http://git/i/1"), eq("RNEW"), any());
    }

    @Test
    public void issue_labeled_should_reject_when_owner_unresolvable() {
        when(idempotencyStore.findRequirementId(anyString(), anyString())).thenReturn(Optional.empty());
        when(scmGateway.parseWebhook(any(WebhookEnvelope.class))).thenReturn(new ScmWebhookEvent.IssueLabeled(
                "http://git/i/1", "标题", "正文", null, List.of("agent-dev")));

        service.handle("uuid-1", "X", "{}");

        verify(appService, never()).createWithRef(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    public void issue_author_missing_from_directory_should_fall_back_to_default_owner() {
        properties.getIntake().setDefaultOwner("V999");
        when(userDirectory.containsUser("outsider")).thenReturn(false);
        when(idempotencyStore.findRequirementId(anyString(), anyString())).thenReturn(Optional.empty());
        when(appService.createWithRef(eq(RequirementSource.GITLAB_ISSUE), eq("http://git/i/2"),
                eq("标题"), eq("正文"), eq("V999"))).thenReturn("RNEW");
        when(scmGateway.parseWebhook(any(WebhookEnvelope.class))).thenReturn(new ScmWebhookEvent.IssueLabeled(
                "http://git/i/2", "标题", "正文", "outsider", List.of("agent-dev")));

        service.handle("uuid-1", "X", "{}");

        verify(appService).createWithRef(any(), anyString(), anyString(), anyString(), eq("V999"));
    }

    @Test
    public void issue_labeled_should_be_idempotent_by_issue_url() {
        when(idempotencyStore.findRequirementId("gitlab-issue", "http://git/i/1"))
                .thenReturn(Optional.of("REXIST"));
        when(scmGateway.parseWebhook(any(WebhookEnvelope.class))).thenReturn(new ScmWebhookEvent.IssueLabeled(
                "http://git/i/1", "标题", "正文", "V1", List.of("agent-dev")));

        service.handle("uuid-1", "X", "{}");

        verify(appService, never()).createWithRef(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    public void dispatch_failure_should_not_propagate() {
        when(scmGateway.parseWebhook(any(WebhookEnvelope.class))).thenReturn(
                new ScmWebhookEvent.MrMerged(7, "req/R1", "x"));
        org.mockito.Mockito.doThrow(new IllegalStateException("wrong state"))
                .when(appService).markDelivered(anyString(), anyString(), anyString());

        assertDoesNotThrow(() -> service.handle("uuid-1", "X", "{}"));
    }

    private Requirement implementingRequirement() {
        Requirement requirement = Requirement.create(RequirementSource.BOARD, "t", "d", "V1");
        requirement.attachPlan(new com.example.agentweb.domain.requirement.AgentPlan(
                "p", null, null, java.time.Instant.now()), "V1");
        requirement.approve("V1");
        requirement.attachWorkspace("W1");
        requirement.startImplement("V1");
        requirement.pullEvents();
        return requirement;
    }
}
