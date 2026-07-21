package com.example.agentweb.app.refinery;

import com.example.agentweb.domain.refinery.RagChunk;

import java.util.List;

/**
 * 召回正文的落盘端口: 把命中 chunk 的正文物化为 workingDir 下的文件,
 * 让 prompt 只带路径、agent 按需 Read. 实现在 infra 层.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-02
 */
public interface RecallDetailStore {

    /**
     * 为每个命中返回 workingDir 相对的正文路径.
     *
     * <p>chunk 自带 {@code detailPath} (如 issue-log 文件) 时直接返回该路径不再物化;
     * 物化失败或 workingDir 不可用时对应元素为 {@code null}, 绝不抛出——召回失败不该让主流程挂.</p>
     *
     * @param workingDir run 的工作目录, 可为 null
     * @param hits 召回命中的 chunk 列表
     * @return 与 hits 等长的路径列表, 元素可为 null
     */
    List<String> store(String workingDir, List<RagChunk> hits);
}
