package com.example.agentweb.app.refinery;

import java.util.List;

/**
 * 低分丢弃记录分页读模型。
 *
 * @author alex
 * @since 2026-07-23
 */
public record DiscardedRefinePage(List<DiscardedRefineView> items, long total, int page, int size) {
}
