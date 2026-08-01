package com.example.agentweb.infra.workspace.document;

import com.example.agentweb.domain.shared.CanonicalHashing;
import lombok.Getter;

import java.util.Arrays;

/**
 * 单次稳定读取后冻结的原始文件字节和版本事实。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
final class StableDocumentSnapshot {

    private final byte[] content;
    private final long size;
    private final long lastModified;
    private final String contentVersion;

    StableDocumentSnapshot(byte[] content, long lastModified) {
        this.content = Arrays.copyOf(content, content.length);
        this.size = content.length;
        this.lastModified = lastModified;
        this.contentVersion = CanonicalHashing.sha256(content);
    }

    public byte[] getContent() {
        return Arrays.copyOf(content, content.length);
    }
}
