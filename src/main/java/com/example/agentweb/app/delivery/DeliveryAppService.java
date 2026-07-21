package com.example.agentweb.app.delivery;

import com.example.agentweb.adapter.delivery.CreateMrCommand;
import com.example.agentweb.adapter.delivery.PushBranchCommand;
import com.example.agentweb.adapter.delivery.ScmCredential;
import com.example.agentweb.adapter.delivery.ScmCredentialStore;
import com.example.agentweb.adapter.delivery.ScmGateway;
import com.example.agentweb.app.requirement.RequirementProperties;
import com.example.agentweb.domain.delivery.DeliveryPolicy;
import com.example.agentweb.domain.delivery.MergeRequestRef;
import com.example.agentweb.domain.requirement.Requirement;
import com.example.agentweb.domain.requirement.RequirementNotFoundException;
import com.example.agentweb.domain.requirement.RequirementRepository;
import com.example.agentweb.domain.workspace.RequirementWorkspace;
import com.example.agentweb.domain.workspace.WorkspaceRepository;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;

/**
 * 交付编排（detailed-design §3.4）：凭证链解析（个人 → 系统默认 → 拒绝）→ push（trailer 回链）
 * → 草稿 MR → merge_request_ref 落库 → 聚合审计事件。规则全在 {@link DeliveryPolicy}，此处只编排。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Slf4j
public class DeliveryAppService {

    private static final String MDC_KEY = "requirementId";

    private final RequirementRepository requirementRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ScmCredentialStore credentialStore;
    private final ScmGateway scmGateway;
    private final MergeRequestStore mergeRequestStore;
    private final RequirementProperties properties;
    private final DeliveryPolicy deliveryPolicy = new DeliveryPolicy();

    public DeliveryAppService(RequirementRepository requirementRepository,
                              WorkspaceRepository workspaceRepository, ScmCredentialStore credentialStore,
                              ScmGateway scmGateway, MergeRequestStore mergeRequestStore,
                              RequirementProperties properties) {
        this.requirementRepository = requirementRepository;
        this.workspaceRepository = workspaceRepository;
        this.credentialStore = credentialStore;
        this.scmGateway = scmGateway;
        this.mergeRequestStore = mergeRequestStore;
        this.properties = properties;
    }

    /**
     * 推分支并创建草稿 MR（幂等语义靠 merge_request_ref 的 UNIQUE upsert 与 GitLab 侧重复分支报错）。
     *
     * @param requirementId 需求 ID
     * @param actor         操作人（trailer Operated-By 判据）
     * @return 创建的 MR 引用
     */
    public MergeRequestRef deliverDraft(String requirementId, String actor) {
        MDC.put(MDC_KEY, requirementId);
        try {
            Requirement requirement = loadRequirement(requirementId);
            RequirementWorkspace workspace = requiredWorkspace(requirementId);
            ScmCredential credential = resolveCredential(requirement.getOwner());

            deliveryPolicy.assertPushRefAllowed(workspace.getBranch());
            List<String> trailers = deliveryPolicy.buildCommitTrailers(
                    sessionUrl(requirementId), actor, credential.isDefaultAccount());
            pushAndReport(workspace, trailers, credential);

            MergeRequestRef mr = createDraftMr(requirement, workspace, credential);
            mergeRequestStore.upsert(requirementId, mr);
            requirement.recordMrDrafted(mr.getUrl(), actor);
            requirementRepository.update(requirement);
            log.info("req-deliver-draft-done requirementId={} mrIid={} usingDefaultAccount={}",
                    requirementId, mr.getMrIid(), credential.isDefaultAccount());
            return mr;
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** 凭证链：需求属主个人凭证 → 系统默认账号 → 拒绝（引导去 git 设置页配置）。 */
    private ScmCredential resolveCredential(String owner) {
        return credentialStore.findPersonal(owner)
                .or(credentialStore::findDefaultAccount)
                .orElseThrow(() -> new CredentialInsufficientException(
                        "无可用 GitLab 凭证: 请在「git 设置」配置个人凭证, 或联系管理员配置系统默认账号"));
    }

    private void pushAndReport(RequirementWorkspace workspace, List<String> trailers,
                               ScmCredential credential) {
        scmGateway.pushBranch(new PushBranchCommand(workspace.getWorktreePath(), workspace.getRepoUrl(),
                workspace.getBranch(), trailers, credential));
    }

    private MergeRequestRef createDraftMr(Requirement requirement, RequirementWorkspace workspace,
                                          ScmCredential credential) {
        String requirementId = requirement.getId().getValue();
        String title = deliveryPolicy.draftTitle(
                "feat(" + requirementId + "): " + requirement.getTitle());
        String description = "需求: " + requirementId + "\n平台回链: " + sessionUrl(requirementId);
        try {
            return scmGateway.createDraftMergeRequest(new CreateMrCommand(workspace.getRepoUrl(),
                    workspace.getBranch(), properties.getDelivery().getDefaultTargetBranch(),
                    title, description, credential));
        } catch (HttpClientErrorException.Forbidden e) {
            throw new CredentialInsufficientException(
                    "GitLab 拒绝了 MR 创建(403): 当前凭证权限不足, 请在「git 设置」配置有项目权限的个人凭证", e);
        }
    }

    /** commit trailer 回链：配置了平台外链 base 用看板链接，否则退化为稳定标识。 */
    private String sessionUrl(String requirementId) {
        String base = properties.getDelivery().getPlatformBaseUrl();
        if (base == null || base.isBlank()) {
            return "requirement:" + requirementId;
        }
        return base.replaceAll("/+$", "") + "/requirement-board.html?rid=" + requirementId;
    }

    private Requirement loadRequirement(String requirementId) {
        Requirement requirement = requirementRepository.findById(requirementId);
        if (requirement == null) {
            throw new RequirementNotFoundException(requirementId);
        }
        return requirement;
    }

    private RequirementWorkspace requiredWorkspace(String requirementId) {
        RequirementWorkspace workspace = workspaceRepository.findByRequirementId(requirementId);
        if (workspace == null) {
            throw new IllegalStateException("deliver rejected: workspace missing for " + requirementId);
        }
        return workspace;
    }
}
