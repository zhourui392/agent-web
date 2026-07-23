package com.example.agentweb.app.worktree;

/**
 * 一次外部命令执行的结果。
 *
 * @param exitCode 退出码, 超时强杀时为 -1
 * @param output   合并 stderr 后的全部输出
 */
public record GitExecResult(int exitCode, String output) {

    public boolean isSuccess() {
        return exitCode == 0;
    }
}
