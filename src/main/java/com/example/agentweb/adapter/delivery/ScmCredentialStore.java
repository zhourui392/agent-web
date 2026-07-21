package com.example.agentweb.adapter.delivery;

import java.util.Optional;

/**
 * SCM 凭证读取端口。凭证解析链的**顺序规则在 app**(个人 → 默认 → 拒绝),
 * 本端口只负责"取得单个来源的凭证",密钥触达(解密/env)收在 infra 实现。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public interface ScmCredentialStore {

    /** 用户个人 GitLab 凭证(GitConfigController 录入),未配置或未启用加密返回 empty */
    Optional<ScmCredential> findPersonal(String userId);

    /** 系统默认账号凭证:env AGENT_GITLAB_DEFAULT_TOKEN 优先,次之 app_setting 加密存储;都无返回 empty */
    Optional<ScmCredential> findDefaultAccount();
}
