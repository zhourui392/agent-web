package com.example.agentweb.infra.workspace.document;

import com.example.agentweb.app.workbench.document.DocumentKind;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 依据安全扩展名白名单和有限字节嗅探集中分类 Workbench 文档。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
public class DocumentTypeResolver {

    private static final Set<String> SOURCE_EXTENSIONS = immutableSet(
            "java", "vue", "js", "mjs", "cjs", "ts", "tsx", "jsx",
            "py", "sql", "css", "scss", "sh", "bash");
    private static final Set<String> STRUCTURED_EXTENSIONS = immutableSet(
            "json", "yaml", "yml", "xml", "toml", "properties", "gradle");
    private static final Set<String> PLAIN_TEXT_EXTENSIONS = immutableSet(
            "txt", "csv", "tsv", "conf", "ini");
    private static final Set<String> REPORT_EXTENSIONS = immutableSet(
            "log", "out", "report");

    DocumentTypeResolution resolve(String relativePath, byte[] content) {
        if (relativePath == null || content == null) {
            throw new IllegalArgumentException("document path and content are required");
        }
        String extension = extension(relativePath);
        if ("svg".equals(extension)) {
            return binary(DocumentKind.BINARY_METADATA, "image/svg+xml");
        }
        DocumentTypeResolution image = image(extension, content);
        if (image != null) {
            return image;
        }
        DocumentKind kind = textKind(extension);
        if (kind == null) {
            return binary(DocumentKind.BINARY_METADATA, "application/octet-stream");
        }
        int bomLength = utf8BomLength(content);
        if (containsNull(content, bomLength) || !isUtf8(content, bomLength)) {
            return binary(DocumentKind.BINARY_METADATA, "application/octet-stream");
        }
        return new DocumentTypeResolution(
                kind, textMediaType(extension, kind), "UTF-8", bomLength);
    }

    private DocumentTypeResolution image(String extension, byte[] content) {
        if ("png".equals(extension) && startsWith(content,
                new byte[]{(byte) 0x89, 'P', 'N', 'G'})) {
            return binary(DocumentKind.IMAGE, "image/png");
        }
        if (("jpg".equals(extension) || "jpeg".equals(extension))
                && startsWith(content,
                new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff})) {
            return binary(DocumentKind.IMAGE, "image/jpeg");
        }
        if ("gif".equals(extension) && startsWith(content,
                new byte[]{'G', 'I', 'F', '8'})) {
            return binary(DocumentKind.IMAGE, "image/gif");
        }
        if ("webp".equals(extension) && content.length >= 12
                && startsWith(content, new byte[]{'R', 'I', 'F', 'F'})
                && content[8] == 'W' && content[9] == 'E'
                && content[10] == 'B' && content[11] == 'P') {
            return binary(DocumentKind.IMAGE, "image/webp");
        }
        return null;
    }

    private DocumentKind textKind(String extension) {
        if ("md".equals(extension) || "markdown".equals(extension)) {
            return DocumentKind.MARKDOWN;
        }
        if (SOURCE_EXTENSIONS.contains(extension)) {
            return DocumentKind.SOURCE_CODE;
        }
        if (STRUCTURED_EXTENSIONS.contains(extension)) {
            return DocumentKind.STRUCTURED_TEXT;
        }
        if (REPORT_EXTENSIONS.contains(extension)) {
            return DocumentKind.LOG_OR_REPORT;
        }
        if (PLAIN_TEXT_EXTENSIONS.contains(extension)) {
            return DocumentKind.PLAIN_TEXT;
        }
        return null;
    }

    private String textMediaType(String extension, DocumentKind kind) {
        if (kind == DocumentKind.MARKDOWN) {
            return "text/markdown";
        }
        if ("java".equals(extension)) {
            return "text/x-java-source";
        }
        if ("vue".equals(extension)) {
            return "text/x-vue";
        }
        if ("js".equals(extension) || "mjs".equals(extension)
                || "cjs".equals(extension) || "jsx".equals(extension)) {
            return "text/javascript";
        }
        if ("ts".equals(extension) || "tsx".equals(extension)) {
            return "text/typescript";
        }
        if ("py".equals(extension)) {
            return "text/x-python";
        }
        if ("sql".equals(extension)) {
            return "application/sql";
        }
        if ("json".equals(extension)) {
            return "application/json";
        }
        if ("yaml".equals(extension) || "yml".equals(extension)) {
            return "application/yaml";
        }
        if ("xml".equals(extension)) {
            return "application/xml";
        }
        return "text/plain";
    }

    private String extension(String relativePath) {
        int slash = relativePath.lastIndexOf('/');
        int dot = relativePath.lastIndexOf('.');
        if (dot <= slash || dot == relativePath.length() - 1) {
            return "";
        }
        return relativePath.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private int utf8BomLength(byte[] content) {
        return startsWith(content,
                new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf}) ? 3 : 0;
    }

    private boolean isUtf8(byte[] content, int start) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content, start, content.length - start));
            return true;
        } catch (CharacterCodingException ex) {
            return false;
        }
    }

    private boolean containsNull(byte[] content, int start) {
        for (int index = start; index < content.length; index++) {
            if (content[index] == 0) {
                return true;
            }
        }
        return false;
    }

    private boolean startsWith(byte[] content, byte[] expected) {
        if (content.length < expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (content[index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private DocumentTypeResolution binary(DocumentKind kind, String mediaType) {
        return new DocumentTypeResolution(kind, mediaType, null, 0);
    }

    private static Set<String> immutableSet(String... values) {
        return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(values)));
    }
}
