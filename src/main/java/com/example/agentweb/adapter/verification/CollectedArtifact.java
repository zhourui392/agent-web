package com.example.agentweb.adapter.verification;

import lombok.Value;

/**
 * 单个验证工件。内容 ≤64KB 时 content 非空,超限时 content 为 null、filePath 指向平台侧文件。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Value
public class CollectedArtifact {

    /** FLOWSTATE | FAILED_CASES | VERIFICATION_RECORD */
    String kind;

    /** 工件内容;超限时为 null */
    String content;

    /** 超限落盘路径;内容内联时为 null */
    String filePath;
}
