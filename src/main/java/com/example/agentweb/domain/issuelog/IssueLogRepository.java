package com.example.agentweb.domain.issuelog;

import java.nio.file.Path;

/**
 * issue-log 仓储端口。负责工作目录下 {@code docs/issue-log/} 的物理读写。
 *
 * <p>实现要点(由 infra 层提供):</p>
 * <ul>
 *   <li>所有写操作必须在按 {@code workingDir} 维度加锁的临界区内完成,
 *       避免多线程并发分配同一 {@code id}</li>
 *   <li>{@link #save(Path, IssueLogDraft)} 应锁内重新调用 {@link #nextId(Path)}
 *       计算 id,不可信赖外部预分配</li>
 *   <li>{@link #ensureInitialized(Path)} 必须幂等</li>
 * </ul>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-19
 */
public interface IssueLogRepository {

    /**
     * 确保工作目录下 {@code docs/issue-log/issue/} 子目录与 {@code INDEX.md} 表头就位。
     * 目录已存在时无操作。
     *
     * @param workingDir 诊断任务的工作目录绝对路径
     */
    void ensureInitialized(Path workingDir);

    /**
     * 根据当前 {@code INDEX.md} 计算下一个可用 id。
     *
     * @param workingDir 工作目录
     * @return 形如 {@code I-023} 的下一个 id
     */
    String nextId(Path workingDir);

    /**
     * 落盘一份 issue-log 草稿:分配 id,写 issue 文件,追加 INDEX 行。
     * 整个过程在按 {@code workingDir} 维度的锁内完成。
     *
     * @param workingDir 工作目录
     * @param draft      经过验证的草稿
     * @return 已分配 id 与最终落盘路径的聚合根
     */
    IssueLogEntry save(Path workingDir, IssueLogDraft draft);

    /**
     * 抽取 {@code INDEX.md} 中已存在的类型与服务清单,供 LLM 精炼与前端下拉候选。
     *
     * @param workingDir 工作目录
     */
    IndexMetadata loadMetadata(Path workingDir);

    /**
     * 读取 {@code INDEX.md} 全文,供候选草稿查重比对。INDEX 不存在时返回空串。
     *
     * @param workingDir 工作目录
     * @return INDEX.md 文本,不存在时为空串
     */
    String loadIndexText(Path workingDir);
}
