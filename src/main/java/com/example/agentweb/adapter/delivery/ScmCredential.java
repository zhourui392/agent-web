package com.example.agentweb.adapter.delivery;

import lombok.Value;

/**
 * SCM 访问凭证(内存态,来自 app 层凭证链解密;禁落库禁进日志)。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Value
public class ScmCredential {

    /** GitLab 账号名 */
    String username;

    /** 明文 token,仅进程内传递 */
    String token;

    /** 是否系统默认账号(true 时 commit trailer 需带 Operated-By) */
    boolean defaultAccount;

    /** toString 打码,防凭证进日志 */
    @Override
    public String toString() {
        return "ScmCredential(username=" + username + ", token=***, defaultAccount=" + defaultAccount + ")";
    }
}
