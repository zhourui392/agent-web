package com.example.agentweb.domain.issuelog;

import lombok.Getter;
import java.util.Collections;
import java.util.List;

/**
 * INDEX.md 内已存在的类型 / 服务清单。供草稿生成时喂给 LLM 做软约束,
 * 同时供前端弹窗下拉框做候选。
 *
 * <p>不变量:两个列表均按"首次出现顺序去重"。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-19
 */
public final class IndexMetadata {

    public static final IndexMetadata EMPTY =
            new IndexMetadata(Collections.emptyList(), Collections.emptyList());

    @Getter
    private final List<String> categories;
    @Getter
    private final List<String> services;

    public IndexMetadata(List<String> categories, List<String> services) {
        this.categories = Collections.unmodifiableList(categories);
        this.services = Collections.unmodifiableList(services);
    }
}
