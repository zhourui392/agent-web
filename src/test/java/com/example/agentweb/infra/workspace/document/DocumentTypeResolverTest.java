package com.example.agentweb.infra.workspace.document;

import com.example.agentweb.app.workbench.document.DocumentKind;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文档类型、UTF-8 与安全预览分类测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class DocumentTypeResolverTest {

    private final DocumentTypeResolver resolver = new DocumentTypeResolver();

    @Test
    void resolveShouldClassifySupportedTextImageAndBinaryTypes() {
        assertAll(
                () -> assertKind("README.md", DocumentKind.MARKDOWN,
                        "text/markdown"),
                () -> assertKind("src/Main.java", DocumentKind.SOURCE_CODE,
                        "text/x-java-source"),
                () -> assertKind("package.json", DocumentKind.STRUCTURED_TEXT,
                        "application/json"),
                () -> assertKind("target/test.log", DocumentKind.LOG_OR_REPORT,
                        "text/plain"),
                () -> assertKind("screenshots/result.png", DocumentKind.IMAGE,
                        "image/png"),
                () -> assertKind("assets/icon.svg", DocumentKind.BINARY_METADATA,
                        "image/svg+xml"),
                () -> assertKind("archive.bin", DocumentKind.BINARY_METADATA,
                        "application/octet-stream"));
    }

    @Test
    void resolveShouldStripUtf8BomAndNeverSplitMultiBytePreview() {
        byte[] text = "你好-world".getBytes(StandardCharsets.UTF_8);
        byte[] bomText = new byte[text.length + 3];
        bomText[0] = (byte) 0xef;
        bomText[1] = (byte) 0xbb;
        bomText[2] = (byte) 0xbf;
        System.arraycopy(text, 0, bomText, 3, text.length);

        DocumentTypeResolution resolution = resolver.resolve("README.md", bomText);
        DocumentTextPreview full = resolution.preview(bomText, bomText.length);
        DocumentTextPreview bounded = resolution.preview(bomText, 4);

        assertAll(
                () -> assertEquals("UTF-8", resolution.getEncoding()),
                () -> assertEquals("你好-world", full.getContent()),
                () -> assertFalse(full.isTruncated()),
                () -> assertEquals("你", bounded.getContent()),
                () -> assertTrue(bounded.isTruncated()),
                () -> assertFalse(bounded.getContent().contains("\ufffd")));
    }

    @Test
    void resolveShouldFailClosedToMetadataForInvalidOrBinaryTextPayload() {
        DocumentTypeResolution invalid = resolver.resolve(
                "README.md", new byte[]{(byte) 0xc3, (byte) 0x28});
        DocumentTypeResolution binary = resolver.resolve(
                "notes.txt", new byte[]{'a', 0, 'b'});

        assertAll(
                () -> assertEquals(DocumentKind.BINARY_METADATA, invalid.getKind()),
                () -> assertEquals("application/octet-stream", invalid.getMediaType()),
                () -> assertNull(invalid.getEncoding()),
                () -> assertNull(invalid.preview(
                        new byte[]{(byte) 0xc3, (byte) 0x28}, 10).getContent()),
                () -> assertEquals(DocumentKind.BINARY_METADATA, binary.getKind()),
                () -> assertNull(binary.preview(new byte[]{'a', 0, 'b'}, 10).getContent()));
    }

    private void assertKind(String path, DocumentKind kind, String mediaType) {
        byte[] content = kind == DocumentKind.IMAGE
                ? new byte[]{(byte) 0x89, 'P', 'N', 'G'}
                : "content".getBytes(StandardCharsets.UTF_8);
        DocumentTypeResolution resolution = resolver.resolve(path, content);
        assertEquals(kind, resolution.getKind());
        assertEquals(mediaType, resolution.getMediaType());
    }
}
