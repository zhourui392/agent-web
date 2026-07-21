package com.example.agentweb.app.requirement;

import com.example.agentweb.adapter.AgentGateway;
import com.example.agentweb.app.StreamOutputExtractor;
import com.example.agentweb.domain.requirement.RequirementQuotaPolicy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 需求线 run 发射器：配额守卫 → 异步执行 → SSE 转发（RunEventBus 广播）→ 结果回调。
 * 各 run 服务只负责前置迁移、prompt 组装与完成处理；执行机械收敛在此。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Slf4j
public class RequirementRunLauncher {

    /** SSE 流 key 前缀：看板经 GET /api/requirements/{id}/run-stream 订阅。 */
    public static final String STREAM_KEY_PREFIX = "req-run-";

    private static final String MDC_KEY = "requirementId";

    private final AgentGateway agentGateway;
    private final RunEventBus eventBus;
    private final StreamOutputExtractor outputExtractor;
    private final RequirementProperties properties;
    private final RequirementRunTracker runTracker;
    private final Executor runExecutor;
    private final RequirementQuotaPolicy quotaPolicy = new RequirementQuotaPolicy();

    public RequirementRunLauncher(AgentGateway agentGateway, RunEventBus eventBus,
                                  StreamOutputExtractor outputExtractor, RequirementProperties properties,
                                  RequirementRunTracker runTracker, Executor runExecutor) {
        this.agentGateway = agentGateway;
        this.eventBus = eventBus;
        this.outputExtractor = outputExtractor;
        this.properties = properties;
        this.runTracker = runTracker;
        this.runExecutor = runExecutor;
    }

    /**
     * 发射一次 run：配额检查在提交前同步做（超限直接抛给调用方），执行与回调在 executor 线程。
     *
     * @param requirementId 需求 ID
     * @param profile       执行参数
     * @param onComplete    进程退出后的完成回调（仅正常退出路径；异常路径只记日志与 SSE）
     */
    public void launch(String requirementId, RunProfile profile, Consumer<RunResult> onComplete) {
        assertRunQuota(requirementId);
        runTracker.increment(requirementId);
        try {
            runExecutor.execute(() -> executeRun(requirementId, profile, onComplete));
        } catch (RuntimeException e) {
            runTracker.decrement(requirementId);
            throw e;
        }
    }

    /**
     * run 配额前置断言：各 run 服务在昂贵动作（provision / doc 拉取）与状态迁移**之前**调用，
     * 避免配额超限把需求卡在中间态；launch 内会再断言一次兜底。
     *
     * @param requirementId 需求 ID
     */
    public void assertRunQuota(String requirementId) {
        quotaPolicy.assertWithinRunQuota(requirementId, runTracker.activeCount(requirementId),
                properties.getQuota().getMaxRunsPerRequirement());
    }

    private void executeRun(String requirementId, RunProfile profile, Consumer<RunResult> onComplete) {
        String streamKey = STREAM_KEY_PREFIX + requirementId;
        MDC.put(MDC_KEY, requirementId);
        log.info("req-run-start requirementId={} kind={} agentType={} workingDir={} timeoutSec={}",
                requirementId, profile.getRunKind(), profile.getAgentType(),
                profile.getWorkingDir(), profile.getTimeoutSeconds());
        try {
            RunResult result = streamProcess(streamKey, profile);
            log.info("req-run-exit requirementId={} kind={} exitCode={} outputLen={}",
                    requirementId, profile.getRunKind(), result.getExitCode(),
                    result.getRawOutput().length());
            onComplete.accept(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("req-run-interrupted requirementId={} kind={}", requirementId, profile.getRunKind());
        } catch (Exception e) {
            log.error("req-run-failed requirementId={} kind={}", requirementId, profile.getRunKind(), e);
            eventBus.publish(streamKey, 0, "error", "run failed: " + e.getMessage());
        } finally {
            runTracker.decrement(requirementId);
            eventBus.close(streamKey);
            MDC.remove(MDC_KEY);
        }
    }

    private RunResult streamProcess(String streamKey, RunProfile profile)
            throws java.io.IOException, InterruptedException {
        StringBuilder fullOutput = new StringBuilder();
        int[] exitCode = {-1};
        agentGateway.runStream(profile.getAgentType(), profile.getWorkingDir(), profile.getAssembledPrompt(),
                streamKey, null, null, profile.getTimeoutSeconds(),
                chunk -> forwardChunk(streamKey, profile, fullOutput, chunk),
                code -> exitCode[0] = code,
                null, profile.getExtraEnv());
        String raw = fullOutput.toString();
        return new RunResult(exitCode[0], raw, outputExtractor.extractPlainText(raw));
    }

    private void forwardChunk(String streamKey, RunProfile profile, StringBuilder fullOutput, String chunk) {
        List<String> normalized = agentGateway.normalizeChunk(profile.getAgentType(), chunk);
        if (normalized == null) {
            return;
        }
        for (String line : normalized) {
            fullOutput.append(line).append('\n');
            eventBus.publish(streamKey, 0, "chunk", line);
        }
    }
}
