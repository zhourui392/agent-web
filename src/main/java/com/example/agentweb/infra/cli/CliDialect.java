package com.example.agentweb.infra.cli;

import com.example.agentweb.domain.shared.AgentType;

import java.util.List;

/**
 * CLI 方言策略。把命令拼装、流事件抽取、归一化这三件 CLI 相关的差异，
 * 从 {@code AgentCliGateway} 中下沉到此接口。
 * <p>每个 {@link AgentType} 对应一个实现 Bean，{@code AgentCliGateway} 根据 type 路由。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-14
 */
public interface CliDialect {

    /**
     * 本方言负责的 agent 类型。
     * @return 对应的 {@link AgentType}
     */
    AgentType type();

    /**
     * 拼装最终启动 CLI 子进程所需的命令行 token 列表。
     * <p>实现应自行处理 resume 逻辑（同位 flag 或子命令位置参数等差异）。</p>
     * @param ctx 命令拼装上下文（含 config/userMessage/workingDir/resumeId 等）
     * @return CLI 启动命令行 token 列表（ProcessBuilder 直接消费）
     */
    List<String> buildCommand(BuildContext ctx);

    /**
     * 从单行 stdout JSON 抽取 CLI 内部 session id（用于后续 resume）。
     * 不是本方言关心、或不含 session id 的事件返回 {@code null}。
     * @param stdoutLine 单行 stdout
     * @return CLI 内部 session id,无法提取时返回 null
     */
    String extractResumeId(String stdoutLine);

    /**
     * 将本方言事件归一化为前端约定的 chunk 文本。
     * <p>返回 0..N 条前端事件: Codex 单条 {@code item.started/command_execution} 会展开为
     * {@code content_block_start} + {@code input_json_delta} 两条; 重连噪音 {@code error}
     * 返回空 List; 普通事件返回 1 条。Claude 直接直通返回单元素 List。</p>
     * @param stdoutLine 单行 stdout
     * @return 归一化后的前端事件 JSON 列表 (0..N 条)
     */
    List<String> normalizeChunk(String stdoutLine);

    /**
     * 判断该 stdout 事件是否标志本轮对话结束。
     * <p>Codex 为 {@code turn.completed} / {@code turn.failed}，Claude 为 {@code result} 事件。
     * 一旦出现，gateway 即可立即收尾，无需再等子进程退出 —— codex 进程退出还有约 1~2s 延迟，
     * 且其在 Windows 派生的孤儿子进程会令 stdout 管道久不闭合。</p>
     * @param stdoutLine 单行 stdout
     * @return true 表示本轮已结束,gateway 可立即收尾
     */
    boolean isTurnEnd(String stdoutLine);
}
