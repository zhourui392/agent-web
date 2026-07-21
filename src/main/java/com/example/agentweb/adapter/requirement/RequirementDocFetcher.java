package com.example.agentweb.adapter.requirement;

/**
 * 需求文档拉取端口:sourceRef(文档 URL) → 正文 markdown。
 *
 * <p>多实现按 supports 链式择一(选择在 app 编排,属路由非业务判断)。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public interface RequirementDocFetcher {

    /** 拉取文档正文 */
    FetchedDoc fetch(String sourceRef);

    /** URL 模式识别:本实现是否认识该 sourceRef */
    boolean supports(String sourceRef);
}
