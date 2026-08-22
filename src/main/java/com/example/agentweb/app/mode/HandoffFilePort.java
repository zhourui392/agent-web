package com.example.agentweb.app.mode;

/**
 * 交接文件读写端口（工作目录相对路径语义）。
 *
 * @author alex
 * @since 2026-08-20
 */
public interface HandoffFilePort {

    /** 写交接文件（以 handoffId 命名），返回工作目录相对路径。 */
    String export(String workingDir, String handoffId, String content);

    /** 读交接文件内容；不存在返回 {@code null}。 */
    String readContent(String workingDir, String relativePath);

    /** 补偿删除（事务失败清理半成品文件）；静默吞掉 IO 异常。 */
    void deleteQuietly(String workingDir, String relativePath);
}
