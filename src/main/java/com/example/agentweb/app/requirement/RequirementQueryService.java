package com.example.agentweb.app.requirement;

import java.util.List;

/**
 * 需求读侧（CQRS 投影，返回 DTO 不返回半截聚合）。接口放 app、实现在 infra（家规）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public interface RequirementQueryService {

    /**
     * 看板列表（按状态分组由前端做）。
     *
     * @param status 状态过滤，null 不过滤
     * @param owner  属主过滤，null 不过滤
     * @return 看板卡片投影
     */
    List<RequirementBoardItem> listBoard(String status, String owner);

    /**
     * 详情投影。
     *
     * @param requirementId 需求 ID
     * @return 详情，未命中 null
     */
    RequirementDetail getDetail(String requirementId);

    /**
     * 事件时间线（迁移审计流水，按发生顺序）。
     *
     * @param requirementId 需求 ID
     * @return 事件投影列表
     */
    List<RequirementEventView> listEvents(String requirementId);

    /**
     * 非终态需求计数（配额判定输入）。
     *
     * @param owner 属主 userId
     * @return 活跃需求数
     */
    int countActiveByOwner(String owner);

    /**
     * 跨需求事件检索（admin 排查入口，detailed-design §0.5：按 actor / 时间段过滤）。
     *
     * @param actor      操作者精确匹配，空 = 不过滤
     * @param fromMillis 起始时间（含，epoch millis），null = 不限
     * @param toMillis   截止时间（含，epoch millis），null = 不限
     * @param limit      最大返回行数（新事件在前）
     * @return 事件检索行
     */
    List<RequirementEventSearchItem> searchEvents(String actor, Long fromMillis, Long toMillis, int limit);
}
