package com.example.agentweb.app.refinery;

import java.util.List;

/**
 * 召回库存分页读模型。
 *
 * @author alex
 * @since 2026-07-23
 */
public record RefineryChunkPage(List<RefineryChunkView> items, long total, int page, int size) {
}
