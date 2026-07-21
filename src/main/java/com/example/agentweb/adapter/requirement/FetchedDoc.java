package com.example.agentweb.adapter.requirement;

import lombok.Value;

import java.time.Instant;

/**
 * 拉取到的需求文档。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Value
public class FetchedDoc {

    /** 文档标题 */
    String title;

    /** 正文 markdown */
    String markdown;

    /** 拉取时间 */
    Instant fetchedAt;
}
