package com.example.agentweb.app.delivery;

import com.example.agentweb.adapter.UserDirectory;
import com.example.agentweb.adapter.delivery.ScmGateway;
import com.example.agentweb.adapter.delivery.ScmWebhookEvent;
import com.example.agentweb.adapter.delivery.WebhookEnvelope;
import com.example.agentweb.app.requirement.RequirementAppService;
import com.example.agentweb.app.requirement.RequirementIdempotencyStore;
import com.example.agentweb.app.requirement.RequirementProperties;
import com.example.agentweb.domain.requirement.IntakeOwnerPolicy;
import com.example.agentweb.domain.requirement.OwnerUnresolvedException;
import com.example.agentweb.domain.requirement.Requirement;
import com.example.agentweb.domain.requirement.RequirementRepository;
import com.example.agentweb.domain.requirement.RequirementSource;
import com.example.agentweb.domain.workspace.RequirementWorkspace;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

/**
 * webhook 事件编排（detailed-design §3.5/§3.7）：UUID 幂等 → 防腐解析 → 分发。
 * PipelineFailed/MrNoteAdded 只生成 fix-run 建议事件（先人工确认再执行，不自动触发）；
 * MrMerged → markDelivered [T10]（actor system:webhook，知识收割随 markDelivered 统一触发，本类不再直连）；
 * IssueLabeled → 建需求（owner 回落链）。
 * 单事件处理失败只记日志——鉴权后的 webhook 永远回 2xx，5xx 会招来 GitLab 重试风暴。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Slf4j
public class ScmWebhookAppService {

    private static final String ACTOR_SYSTEM_WEBHOOK = "system:webhook";

    /** issue 幂等去重的命名空间（复用 requirement_intake_dedup 的 api_key_name 列）。 */
    private static final String ISSUE_DEDUP_NAMESPACE = "gitlab-issue";

    private static final String BRANCH_PREFIX = "req/";

    private final ScmGateway scmGateway;
    private final WebhookDedupStore dedupStore;
    private final RequirementRepository requirementRepository;
    private final RequirementAppService requirementAppService;
    private final RequirementIdempotencyStore idempotencyStore;
    private final RequirementProperties properties;
    private final UserDirectory userDirectory;
    private final IntakeOwnerPolicy ownerPolicy = new IntakeOwnerPolicy();

    public ScmWebhookAppService(ScmGateway scmGateway, WebhookDedupStore dedupStore,
                                RequirementRepository requirementRepository,
                                RequirementAppService requirementAppService,
                                RequirementIdempotencyStore idempotencyStore,
                                RequirementProperties properties,
                                UserDirectory userDirectory) {
        this.scmGateway = scmGateway;
        this.dedupStore = dedupStore;
        this.requirementRepository = requirementRepository;
        this.requirementAppService = requirementAppService;
        this.idempotencyStore = idempotencyStore;
        this.properties = properties;
        this.userDirectory = userDirectory;
    }

    /**
     * 处理一个已通过鉴权的 webhook（controller 只传原始字段，adapter 类型不出 app 层——守 ArchUnit A2）。
     *
     * @param eventUuid X-Gitlab-Event-UUID（可空 = 不去重）
     * @param eventType X-Gitlab-Event 头
     * @param rawBody   原始 JSON body
     */
    public void handle(String eventUuid, String eventType, String rawBody) {
        if (eventUuid != null && !eventUuid.isBlank()
                && !dedupStore.tryMarkProcessed(eventUuid, Instant.now())) {
            log.info("scm-webhook-duplicate eventUuid={}", eventUuid);
            return;
        }
        ScmWebhookEvent event = scmGateway.parseWebhook(new WebhookEnvelope(eventType, rawBody));
        try {
            dispatch(event);
        } catch (RuntimeException e) {
            log.warn("scm-webhook-dispatch-failed eventType={} reason={}", eventType, e.getMessage(), e);
        }
    }

    private void dispatch(ScmWebhookEvent event) {
        if (event instanceof ScmWebhookEvent.MrMerged merged) {
            onMrMerged(merged);
        } else if (event instanceof ScmWebhookEvent.PipelineFailed failed) {
            onPipelineFailed(failed);
        } else if (event instanceof ScmWebhookEvent.MrNoteAdded note) {
            onMrNoteAdded(note);
        } else if (event instanceof ScmWebhookEvent.IssueLabeled issue) {
            onIssueLabeled(issue);
        } else if (event instanceof ScmWebhookEvent.Unsupported unsupported) {
            log.debug("scm-webhook-unsupported eventType={}", unsupported.eventType());
        }
    }

    private void onMrMerged(ScmWebhookEvent.MrMerged merged) {
        String requirementId = requirementIdOf(merged.sourceBranch());
        if (requirementId == null) {
            return;
        }
        String deliveryRef = "MR !" + merged.mrIid() + " merged by " + merged.mergedByUsername();
        requirementAppService.markDelivered(requirementId, ACTOR_SYSTEM_WEBHOOK, deliveryRef);
    }

    private void onPipelineFailed(ScmWebhookEvent.PipelineFailed failed) {
        recordSuggestion(requirementIdOf(failed.ref()),
                "pipeline 失败[" + failed.status() + "]: " + failed.pipelineUrl());
    }

    private void onMrNoteAdded(ScmWebhookEvent.MrNoteAdded note) {
        recordSuggestion(requirementIdOf(note.sourceBranch()),
                "MR !" + note.mrIid() + " 新评论(" + note.authorUsername() + "): " + note.noteExcerpt());
    }

    private void onIssueLabeled(ScmWebhookEvent.IssueLabeled issue) {
        if (idempotencyStore.findRequirementId(ISSUE_DEDUP_NAMESPACE, issue.issueUrl()).isPresent()) {
            log.info("scm-webhook-issue-already-intaken issueUrl={}", issue.issueUrl());
            return;
        }
        String owner;
        try {
            owner = ownerPolicy.resolveOwner(issue.authorUsername(),
                    properties.getIntake().getDefaultOwner(), userDirectory::containsUser);
        } catch (OwnerUnresolvedException e) {
            log.warn("scm-webhook-issue-rejected issueUrl={} reason={}", issue.issueUrl(), e.getMessage());
            return;
        }
        String requirementId = requirementAppService.createWithRef(RequirementSource.GITLAB_ISSUE,
                issue.issueUrl(), issue.title(), issue.description(), owner);
        idempotencyStore.record(ISSUE_DEDUP_NAMESPACE, issue.issueUrl(), requirementId, Instant.now());
        log.info("scm-webhook-issue-intaken issueUrl={} requirementId={} owner={}",
                issue.issueUrl(), requirementId, owner);
    }

    private void recordSuggestion(String requirementId, String detail) {
        if (requirementId == null) {
            return;
        }
        Requirement requirement = requirementRepository.findById(requirementId);
        if (requirement == null) {
            log.debug("scm-webhook-suggestion-skip reason=requirement-not-found id={}", requirementId);
            return;
        }
        requirement.recordFixSuggestion(ACTOR_SYSTEM_WEBHOOK, detail);
        requirementRepository.update(requirement);
    }

    /** req/&lt;需求号&gt; → 需求号；分支命名唯一事实源是 {@link RequirementWorkspace#branchFor}。 */
    private String requirementIdOf(String branch) {
        if (branch == null || !branch.startsWith(BRANCH_PREFIX)) {
            log.debug("scm-webhook-branch-unmapped branch={}", branch);
            return null;
        }
        String requirementId = branch.substring(BRANCH_PREFIX.length());
        return requirementId.isBlank() ? null : requirementId;
    }
}
