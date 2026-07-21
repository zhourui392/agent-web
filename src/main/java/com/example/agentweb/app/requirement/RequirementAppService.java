package com.example.agentweb.app.requirement;

import com.example.agentweb.app.knowledge.KnowledgeHarvestService;
import com.example.agentweb.domain.requirement.AgentPlan;
import com.example.agentweb.domain.requirement.Requirement;
import com.example.agentweb.domain.requirement.RequirementNotFoundException;
import com.example.agentweb.domain.requirement.RequirementQuotaPolicy;
import com.example.agentweb.domain.requirement.RequirementRepository;
import com.example.agentweb.domain.requirement.RequirementSource;
import com.example.agentweb.domain.requirement.RequirementStatus;
import com.example.agentweb.domain.verification.VerificationOutcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 需求线 app 编排：仅 load → 聚合方法 → update，业务规则全在聚合与域策略。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Slf4j
@Service
public class RequirementAppService {

    /** ID 生日碰撞（日期+4 位随机数）重试上限：碰撞率 ~0.5%/日@10 单，重试 2 次即可忽略。 */
    private static final int MAX_ID_COLLISION_RETRIES = 2;

    private final RequirementRepository repository;
    private final RequirementQueryService queryService;
    private final RequirementProperties properties;
    private final KnowledgeHarvestService knowledgeHarvestService;
    private final RequirementQuotaPolicy quotaPolicy = new RequirementQuotaPolicy();

    /**
     * @param knowledgeHarvestService 交付后知识收割通道，可空（未装配 / 关闭时降级为不收割）
     */
    public RequirementAppService(RequirementRepository repository,
                                 RequirementQueryService queryService,
                                 RequirementProperties properties,
                                 @Nullable KnowledgeHarvestService knowledgeHarvestService) {
        this.repository = repository;
        this.queryService = queryService;
        this.properties = properties;
        this.knowledgeHarvestService = knowledgeHarvestService;
    }

    /**
     * 创建需求：配额检查（读侧计数 → 域策略判定）先于落库。
     *
     * @param title       标题
     * @param description 描述
     * @param owner       属主 userId（取自 UserContext，不信任请求体）
     * @param source      来源通道
     * @return 新需求 ID
     */
    public String create(String title, String description, String owner, RequirementSource source) {
        return createWithRef(source, null, title, description, owner);
    }

    /**
     * 带来源引用创建（M2 GitLab issue / 外部 REST 接入），配额闸与看板直建同一条。
     *
     * @param source      来源通道
     * @param sourceRef   来源引用（issue URL 等），可空
     * @param title       标题
     * @param description 描述
     * @param owner       属主 userId
     * @return 新需求 ID
     */
    public String createWithRef(RequirementSource source, String sourceRef, String title,
                                String description, String owner) {
        int activeCount = queryService.countActiveByOwner(owner);
        quotaPolicy.assertWithinActiveQuota(owner, activeCount,
                properties.getQuota().getMaxActivePerUser());
        for (int attempt = 0; ; attempt++) {
            Requirement requirement = Requirement.create(source, sourceRef, title, description, owner);
            try {
                repository.save(requirement);
                return requirement.getId().getValue();
            } catch (DataAccessException e) {
                if (attempt >= MAX_ID_COLLISION_RETRIES || !isIdCollision(e)) {
                    throw e;
                }
                log.warn("requirement-id-collision id={} attempt={} 换新 ID 重试",
                        requirement.getId().getValue(), attempt + 1);
            }
        }
    }

    /** ID 碰撞判定：标准翻译 DuplicateKeyException，或 SQLite 方言下未翻译但根因点名 requirement.id。 */
    private boolean isIdCollision(DataAccessException e) {
        if (e instanceof DuplicateKeyException) {
            return true;
        }
        String rootMessage = String.valueOf(e.getMostSpecificCause().getMessage());
        return rootMessage.contains("requirement.id");
    }

    public void attachPlan(String requirementId, String planText, String actor) {
        Requirement requirement = load(requirementId);
        requirement.attachPlan(new AgentPlan(planText, null, null, Instant.now()), actor);
        repository.update(requirement);
    }

    public void rejectPlan(String requirementId, String actor, String reason) {
        Requirement requirement = load(requirementId);
        requirement.rejectPlan(actor, reason);
        repository.update(requirement);
    }

    public void approve(String requirementId, String actor) {
        Requirement requirement = load(requirementId);
        requirement.approve(actor);
        repository.update(requirement);
    }

    public void startImplement(String requirementId, String actor) {
        Requirement requirement = load(requirementId);
        requirement.startImplement(actor);
        repository.update(requirement);
    }

    public void startFixRun(String requirementId, String actor) {
        Requirement requirement = load(requirementId);
        requirement.startFixRun(actor);
        repository.update(requirement);
    }

    public void startVerify(String requirementId, String actor) {
        Requirement requirement = load(requirementId);
        requirement.startVerify(actor);
        repository.update(requirement);
    }

    /**
     * 应用验证终态并返回迁移后的状态（供验证编排据 SUSPENDED 触发值班通知，避免 app 层重演聚合规则）。
     *
     * @param requirementId 需求 ID
     * @param outcome       验证终态
     * @param actor         操作者（系统路径记 system:verify）
     * @return 迁移后的状态
     */
    public RequirementStatus applyVerificationOutcome(String requirementId, VerificationOutcome outcome,
                                                      String actor) {
        Requirement requirement = load(requirementId);
        requirement.applyVerificationOutcome(outcome, actor);
        repository.update(requirement);
        return requirement.getStatus();
    }

    public void markDelivered(String requirementId, String actor, String mrRef) {
        Requirement requirement = load(requirementId);
        requirement.markDelivered(actor, mrRef);
        repository.update(requirement);
        harvestKnowledgeQuietly(requirementId, mrRef);
    }

    /**
     * 交付后知识收割：webhook 与手动 /deliver 两条路径都经 markDelivered，统一在此触发（单一收敛点）。
     * 收割自身 quietly 吞异常；此处仅做可空降级，绝不影响交付状态迁移。
     */
    private void harvestKnowledgeQuietly(String requirementId, String mrRef) {
        if (knowledgeHarvestService != null) {
            knowledgeHarvestService.harvestOnDelivered(requirementId, mrRef);
        }
    }

    public void requestChanges(String requirementId, String actor, String reason) {
        Requirement requirement = load(requirementId);
        requirement.requestChanges(actor, reason);
        repository.update(requirement);
    }

    public void suspend(String requirementId, String actor, String reason) {
        Requirement requirement = load(requirementId);
        requirement.suspend(actor, reason);
        repository.update(requirement);
    }

    public void resume(String requirementId, String actor) {
        Requirement requirement = load(requirementId);
        requirement.resume(actor);
        repository.update(requirement);
    }

    public void archive(String requirementId, String actor, String reason) {
        Requirement requirement = load(requirementId);
        requirement.archive(actor, reason);
        repository.update(requirement);
    }

    private Requirement load(String requirementId) {
        Requirement requirement = repository.findById(requirementId);
        if (requirement == null) {
            throw new RequirementNotFoundException(requirementId);
        }
        return requirement;
    }
}
