package com.example.agentweb.app.requirement;

import lombok.Value;

/**
 * 需求线 run 的执行结果（发射器回调载荷）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Value
public class RunResult {

    /** 进程退出码，-1 表示超时被强制终止 */
    int exitCode;

    /** 归一化后的全量输出（NDJSON 行） */
    String rawOutput;

    /** 从输出提取的纯文本（计划正文等） */
    String plainText;
}
