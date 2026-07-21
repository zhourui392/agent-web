package com.example.agentweb.domain.delivery;

import lombok.Value;

/**
 * MR 引用 VO:平台侧对 GitLab merge request 的最小投影。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Value
public class MergeRequestRef {

    /** GitLab 项目内 MR 编号(iid) */
    long mrIid;

    /** MR 页面地址 */
    String url;

    /** 是否草稿 */
    boolean draft;

    /** 最近一次 pipeline 状态,可空(尚未触发) */
    String pipelineStatus;
}
