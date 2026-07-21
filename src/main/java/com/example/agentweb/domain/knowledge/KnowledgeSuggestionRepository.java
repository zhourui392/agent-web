package com.example.agentweb.domain.knowledge;

/**
 * 知识建议收件箱仓储（写侧，domain 契约）：仅聚合 lifecycle，签名只出现 domain 类型；
 * 收件箱列表投影拆在 app 层 KnowledgeInboxQueryService（CQRS 读写分治）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public interface KnowledgeSuggestionRepository {

    void save(KnowledgeSuggestion suggestion);

    void update(KnowledgeSuggestion suggestion);

    KnowledgeSuggestion findById(String id);

    /** 同一需求是否已有候选（关单收割幂等闸，避免 webhook 重放产重复候选）。 */
    boolean existsForRequirement(String requirementId);
}
