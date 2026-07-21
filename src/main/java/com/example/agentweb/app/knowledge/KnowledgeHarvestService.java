package com.example.agentweb.app.knowledge;

import com.example.agentweb.domain.knowledge.KnowledgeScope;
import com.example.agentweb.domain.knowledge.KnowledgeSuggestion;
import com.example.agentweb.domain.knowledge.KnowledgeSuggestionRepository;
import com.example.agentweb.domain.requirement.Requirement;
import com.example.agentweb.domain.requirement.RequirementRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * 关单收割（M4）：需求 DELIVERED [T10] 后把标题/描述/计划收割成知识候选进收件箱，
 * 人工审批后才落盘——诊断线的对应物是 IssueLogBackfillService 候选挖掘。
 * 收割是旁路：失败只降级，绝不影响交付状态迁移；每需求至多收割一次（webhook 重放幂等）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Slf4j
public class KnowledgeHarvestService {

    /** 计划正文进候选 rootCause 的截断上限（收件箱是索引不是全文档，全文随需求可回看）。 */
    private static final int PLAN_EXCERPT_MAX_CHARS = 2000;

    private final KnowledgeSuggestionRepository suggestionRepository;
    private final RequirementRepository requirementRepository;

    public KnowledgeHarvestService(KnowledgeSuggestionRepository suggestionRepository,
                                   RequirementRepository requirementRepository) {
        this.suggestionRepository = suggestionRepository;
        this.requirementRepository = requirementRepository;
    }

    /**
     * 交付后收割知识候选（quietly：任何失败只记日志）。
     *
     * @param requirementId 需求 ID
     * @param sourceRef     交付来源（如 "MR !12 merged by xxx"）
     */
    public void harvestOnDelivered(String requirementId, String sourceRef) {
        try {
            harvest(requirementId, sourceRef);
        } catch (RuntimeException e) {
            log.warn("knowledge-harvest-failed requirementId={} reason={}", requirementId,
                    e.getMessage(), e);
        }
    }

    private void harvest(String requirementId, String sourceRef) {
        if (suggestionRepository.existsForRequirement(requirementId)) {
            log.info("knowledge-harvest-skip-existing requirementId={}", requirementId);
            return;
        }
        Requirement requirement = requirementRepository.findById(requirementId);
        if (requirement == null) {
            log.warn("knowledge-harvest-skip-missing requirementId={}", requirementId);
            return;
        }
        KnowledgeSuggestion suggestion = KnowledgeSuggestion.create(
                requirementId,
                KnowledgeScope.REPO,
                sourceRef,
                "需求交付沉淀: " + requirement.getTitle(),
                requirement.getDescription(),
                planExcerpt(requirement),
                "交付完成，方案以合入 MR 为准: " + (sourceRef == null ? "" : sourceRef));
        suggestionRepository.save(suggestion);
        log.info("knowledge-harvest-created requirementId={} suggestionId={}",
                requirementId, suggestion.getId());
    }

    private String planExcerpt(Requirement requirement) {
        if (requirement.getPlan() == null || requirement.getPlan().getPlanText() == null) {
            return "";
        }
        String planText = requirement.getPlan().getPlanText();
        return planText.length() <= PLAN_EXCERPT_MAX_CHARS
                ? planText
                : planText.substring(0, PLAN_EXCERPT_MAX_CHARS);
    }
}
