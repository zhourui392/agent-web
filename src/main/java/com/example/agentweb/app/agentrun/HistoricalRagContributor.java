package com.example.agentweb.app.agentrun;

import com.example.agentweb.domain.refinery.SourceType;
import org.springframework.stereotype.Component;

/**
 * Historical RAG contributor. 诊断历史召回器已随诊断子系统摘除，历史召回通道恒不可用：
 * 关闭态记 disabled，DIAGNOSE 源（仅存量数据可能出现）记 notApplicable，不再注入历史前缀。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
@Component
public class HistoricalRagContributor implements PromptContributor {

    @Override
    public void append(PromptAssembly assembly) {
        RunRecallPolicy policy = assembly.getContext().getRecallPolicy();
        if (!policy.isHistoricalRagEnabled()
                || policy.getHistoricalSourceFilter() != SourceType.DIAGNOSE) {
            assembly.addRecallContribution(RecallContribution.disabled(RecallChannel.HISTORICAL_RAG));
            return;
        }
        assembly.addRecallContribution(RecallContribution.notApplicable(
                RecallChannel.HISTORICAL_RAG, policy.getTopK()));
    }
}
