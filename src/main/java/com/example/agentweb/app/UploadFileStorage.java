package com.example.agentweb.app;

import java.io.IOException;

/**
 * 会话附件上传存储端口。app / interfaces 层只见此端口, 文件系统落盘与内容嗅探由 infra 实现。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public interface UploadFileStorage {

    /**
     * 按会话隔离保存附件。
     *
     * @return 落盘后的绝对路径
     */
    String save(String workingDir, String sessionId, String originalName, byte[] content) throws IOException;

    /** 会话删除时清理 {@code upload_file/<sessionId>/} 目录。 */
    void deleteSessionFiles(String workingDir, String sessionId);
}
