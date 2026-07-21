package com.example.agentweb.domain.git;

/**
 * Git 配置授权领域服务：判定当前调用是否缺少登录用户上下文。
 *
 * <p>后台任务等无登录上下文的系统路径不可保存个人 Git 配置，也不向子进程注入身份；
 * 该不变量收口在领域，禁止应用层重复判断。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
public class GitConfigPolicy {

    /**
     * 是否为无用户上下文的系统调用（不可改、不注入）。
     *
     * @param userId 待判定用户工号，{@code null}/空白视为无上下文
     * @return 无用户上下文时返回 true
     */
    public boolean isSystemContext(String userId) {
        return userId == null || userId.trim().isEmpty();
    }
}
