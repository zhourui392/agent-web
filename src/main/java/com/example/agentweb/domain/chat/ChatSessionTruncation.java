package com.example.agentweb.domain.chat;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 会话截断前由聚合计算出的领域计划。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Getter
@RequiredArgsConstructor
public class ChatSessionTruncation {

    private final String prefillContent;
    private final boolean resumeIdPresent;
}
