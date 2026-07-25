package com.example.agentweb.app.harness;

/**
 * SENSITIVE 分级 Artifact 不允许下载。
 *
 * <p>下载端点对 {@code classification=SENSITIVE} 的 Artifact 拒绝返回正文，
 * 避免敏感产物经下载接口外泄；审核应在管理台界面完成。</p>
 *
 * @author alex
 * @since 2026-07-25
 */
public class HarnessSensitiveArtifactException extends RuntimeException {

    public HarnessSensitiveArtifactException(String runId, String artifact) {
        super("Harness Artifact is sensitive and not downloadable: " + runId + "/" + artifact);
    }
}