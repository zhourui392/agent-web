package com.example.agentweb.adapter.workspace;

import lombok.Value;

/**
 * 工作区供给请求。baseRef 为空时取远端默认分支（HEAD 指向）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Value
public class ProvisionRequest {

    String repoUrl;
    String requirementId;

    /** 平台分支命名空间 req/&lt;requirementId&gt;，合法性由聚合构造期保证。 */
    String branch;

    String baseRef;
}
