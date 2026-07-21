package com.example.agentweb.app.knowledge;

import java.util.List;

/**
 * 收件箱读侧查询（CQRS）：列表投影不经聚合，返回 DTO；接口放 app 层，infra 提供实现。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public interface KnowledgeInboxQueryService {

    List<KnowledgeSuggestionView> listByStatus(String status, int limit);
}
