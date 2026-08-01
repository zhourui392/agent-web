package com.example.agentweb.infra.workspace.document;

import com.example.agentweb.app.workbench.document.DocumentKind;
import lombok.Getter;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 单次文件字节嗅探后的渲染类型与 UTF-8 解码策略。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
final class DocumentTypeResolution {

    private final DocumentKind kind;
    private final String mediaType;
    private final String encoding;
    private final int byteOrderMarkLength;

    DocumentTypeResolution(
            DocumentKind kind, String mediaType, String encoding,
            int byteOrderMarkLength) {
        this.kind = kind;
        this.mediaType = mediaType;
        this.encoding = encoding;
        this.byteOrderMarkLength = byteOrderMarkLength;
    }

    DocumentTextPreview preview(byte[] source, int maximumBytes) {
        if (source == null) {
            throw new IllegalArgumentException("document preview source is required");
        }
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("document preview limit must be positive");
        }
        if (encoding == null) {
            return new DocumentTextPreview(null, false);
        }
        int end = Math.min(source.length, byteOrderMarkLength + maximumBytes);
        boolean truncated = end < source.length;
        String decoded = decodePrefix(source, byteOrderMarkLength, end, truncated);
        return new DocumentTextPreview(decoded, truncated);
    }

    private String decodePrefix(
            byte[] source, int start, int initialEnd, boolean truncated) {
        int minimum = Math.max(start, initialEnd - 3);
        for (int end = initialEnd; end >= minimum; end--) {
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(source, start, end - start))
                        .toString();
            } catch (CharacterCodingException ex) {
                if (!truncated) {
                    return null;
                }
            }
        }
        return "";
    }
}
