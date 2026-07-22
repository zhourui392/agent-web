package com.example.agentweb.app.chatrun;

import java.util.List;
import java.util.Optional;

/**
 * CQRS read-side port for resumable chat-run observability (metrics overview + per-run diagnostics).
 *
 * <p>纯 SELECT 投影 + 内存 gauge,返回 DTO,不经聚合根;供管理端指标与排障使用。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public interface ChatRunMetricsQueryService {

    /** 子系统规模、分布、终态、失败码与实时订阅 gauge 的聚合总览。 */
    ChatRunMetricsOverview overview();

    /**
     * 最近若干个 run 的诊断视图,按创建时间降序。
     *
     * @param limit 返回条数 (调用方负责 clamp 到合理范围)
     */
    List<ChatRunDiagnosticView> recentRuns(int limit);

    /** 单个 run 的诊断视图;不存在时 empty。 */
    Optional<ChatRunDiagnosticView> diagnose(String runId);
}
