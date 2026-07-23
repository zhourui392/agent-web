package com.example.agentweb.app;

import java.io.IOException;

/**
 * 会话图片上传存储端口。app / interfaces 层只见此端口, 文件系统落盘细节由 infra 实现。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public interface UploadPicStorage {

    /**
     * 无会话归属的调用方(intake 等)落平铺 {@code upload_pic/} 目录。
     *
     * @return 落盘后的绝对路径
     */
    String save(String workingDir, byte[] content) throws IOException;

    /**
     * chat 调用方按会话隔离落 {@code upload_pic/<sessionId>/}。
     *
     * @return 落盘后的绝对路径
     */
    String save(String workingDir, String sessionId, byte[] content) throws IOException;

    /** 会话删除时清理 {@code upload_pic/<sessionId>/} 目录。 */
    void deleteSessionImages(String workingDir, String sessionId);
}
