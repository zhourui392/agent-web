package com.example.agentweb.infra.cli;

import com.example.agentweb.domain.shared.AgentType;

/**
 * 短时同步 CLI 调用端口。专为 issue-log 精炼这类
 * "一次性请求-响应"场景而设;不创建 ChatSession / DiagnoseTask,
 * 阻塞直到子进程退出或超时。
 *
 * <p>与 {@link com.example.agentweb.adapter.AgentGateway#runOnce}的关键差异:</p>
 * <ul>
 *   <li>显式 {@code timeoutSeconds} 参数,不复用 agent 全局超时</li>
 *   <li>返回值已经过 stream-json 抽取,直接是 agent 最终结论文本</li>
 *   <li>失败统一抛 {@link CliInvokeException},不暴露 IO / 中断异常</li>
 * </ul>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-19
 */
public interface AgentCliInvoker {

    /**
     * 阻塞调用 CLI,返回抽取后的纯文本结论。
     *
     * @param type           agent 类型(Claude/Codex)
     * @param workingDir     工作目录绝对路径
     * @param prompt         喂给 CLI 的完整 prompt 文本
     * @param timeoutSeconds 硬超时(秒)
     * @return CLI 输出经 stream-json 抽取后的纯文本
     * @throws CliInvokeException 超时 / 进程非零退码 / IO 失败
     */
    String invokeSync(AgentType type, String workingDir, String prompt, long timeoutSeconds)
            throws CliInvokeException;

    /**
     * 同 {@link #invokeSync(AgentType, String, String, long)}, 但用 {@code model} 覆盖本次调用的模型。
     *
     * <p>仅作用于这一次同步调用, 不影响流式对话 / 其他 invokeSync 调用。{@code model} 为空 / null
     * 时不下发 {@code --model}, 行为与四参版完全一致 (走 CLI 默认模型)。</p>
     *
     * <p>用途: refinery 评分这类高频、低风险任务可单独指定廉价模型 (如 claude-haiku-4-5-20251001),
     * 而主对话保持强模型。</p>
     *
     * @param model 本次调用使用的模型名; 空 / null 表示不覆盖
     */
    String invokeSync(AgentType type, String workingDir, String prompt, long timeoutSeconds, String model)
            throws CliInvokeException;
}
