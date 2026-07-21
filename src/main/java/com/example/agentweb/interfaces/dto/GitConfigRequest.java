package com.example.agentweb.interfaces.dto;

import lombok.Data;

/**
 * 保存用户 git 配置请求体。身份合法性校验下沉到 {@code GitIdentity.of}，凭证密码只入不回显。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
@Data
public class GitConfigRequest {

    /** 提交者姓名。 */
    private String name;

    /** 提交者邮箱。 */
    private String email;

    /** push 用户名（明文，可空）。 */
    private String credUsername;

    /** push 密码 / token（可空；空表示不改既有凭证，绝不回显）。 */
    private String credPassword;
}
