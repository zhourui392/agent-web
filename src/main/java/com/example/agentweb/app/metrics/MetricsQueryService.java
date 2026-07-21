package com.example.agentweb.app.metrics;

import java.util.List;

/**
 * 管理后台指标读侧(CQRS 查询端口)。纯 SELECT 投影,返回 DTO,不经聚合根。
 *
 * <p>接口置于 app 层,实现走 infra 直连 {@code JdbcTemplate};调用方(Controller)只依赖此接口。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
public interface MetricsQueryService {

    /**
     * 总览指标:会话 / 诊断 / 飞书接入三块的规模、分布与关键比率。
     */
    MetricsOverview overview();

    /**
     * 最近 {@code days} 天的每日会话数 / 诊断数趋势,按 UTC 日期升序,缺数据的日期补 0。
     *
     * @param days 回溯天数(调用方负责 clamp 到合理范围)
     */
    List<DailyTrendPoint> trend(int days);
}
