package com.example.agentweb.domain.workbench;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 浏览器上传附件的有限媒体类型、扩展名和内容签名策略。
 *
 * @author alex
 * @since 2026-08-01
 */
enum UploadedAttachmentMediaType {

    PNG("image/png", UploadedAttachmentContentSignature.PNG, "png"),
    JPEG("image/jpeg", UploadedAttachmentContentSignature.JPEG, "jpg", "jpeg"),
    GIF("image/gif", UploadedAttachmentContentSignature.GIF, "gif"),
    WEBP("image/webp", UploadedAttachmentContentSignature.WEBP, "webp"),
    PDF("application/pdf", UploadedAttachmentContentSignature.PDF, "pdf"),
    PLAIN("text/plain", UploadedAttachmentContentSignature.TEXT, "txt", "log"),
    MARKDOWN("text/markdown", UploadedAttachmentContentSignature.TEXT, "md", "markdown"),
    JSON("application/json", UploadedAttachmentContentSignature.TEXT, "json"),
    XML("application/xml", UploadedAttachmentContentSignature.TEXT, "xml"),
    CSV("text/csv", UploadedAttachmentContentSignature.TEXT, "csv"),
    YAML("application/yaml", UploadedAttachmentContentSignature.TEXT, "yaml", "yml"),
    TOML("application/toml", UploadedAttachmentContentSignature.TEXT, "toml"),
    JAVA("text/x-java-source", UploadedAttachmentContentSignature.TEXT, "java"),
    KOTLIN("text/x-kotlin", UploadedAttachmentContentSignature.TEXT, "kt", "kts"),
    JAVASCRIPT("text/javascript", UploadedAttachmentContentSignature.TEXT,
            "js", "mjs", "cjs"),
    TYPESCRIPT("text/typescript", UploadedAttachmentContentSignature.TEXT, "ts", "tsx"),
    VUE("text/x-vue", UploadedAttachmentContentSignature.TEXT, "vue"),
    PYTHON("text/x-python", UploadedAttachmentContentSignature.TEXT, "py"),
    GO("text/x-go", UploadedAttachmentContentSignature.TEXT, "go"),
    RUST("text/x-rust", UploadedAttachmentContentSignature.TEXT, "rs"),
    C_SOURCE("text/x-c", UploadedAttachmentContentSignature.TEXT, "c", "h"),
    CPP_SOURCE("text/x-c++", UploadedAttachmentContentSignature.TEXT,
            "cc", "cpp", "cxx", "hpp"),
    SQL("application/sql", UploadedAttachmentContentSignature.TEXT, "sql"),
    PROPERTIES("text/x-java-properties", UploadedAttachmentContentSignature.TEXT,
            "properties");

    private static final Map<String, UploadedAttachmentMediaType> BY_EXTENSION =
            byExtension();
    private static final Set<String> EXECUTABLE_EXTENSIONS =
            Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
                    "exe", "dll", "com", "bat", "cmd", "msi", "class",
                    "jar", "war", "so", "dylib", "app", "apk", "deb",
                    "rpm", "ps1", "sh", "bash", "zsh")));
    private static final Set<String> GENERIC_CLIENT_MEDIA_TYPES =
            Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
                    "application/octet-stream", "binary/octet-stream")));

    private final String mediaType;
    private final UploadedAttachmentContentSignature signature;
    private final Set<String> extensions;

    UploadedAttachmentMediaType(
            String mediaType, UploadedAttachmentContentSignature signature,
            String... extensions) {
        this.mediaType = mediaType;
        this.signature = signature;
        this.extensions = Collections.unmodifiableSet(
                new HashSet<String>(Arrays.asList(extensions)));
    }

    static UploadedAttachmentMediaType requireTrusted(
            String displayName, String clientMediaType,
            UploadedAttachmentContentSignature observedSignature) {
        String extension = extension(displayName);
        if (EXECUTABLE_EXTENSIONS.contains(extension)
                || isExecutable(observedSignature)) {
            throw invalid("executable attachments are not accepted");
        }
        UploadedAttachmentMediaType expected = BY_EXTENSION.get(extension);
        if (expected == null || observedSignature == null
                || expected.signature != observedSignature) {
            throw invalid(
                    "attachment extension and content signature do not match");
        }
        String client = normalizeClientMediaType(clientMediaType);
        if (client != null && !GENERIC_CLIENT_MEDIA_TYPES.contains(client)
                && !expected.acceptsClientMediaType(client)) {
            throw invalid(
                    "attachment media type contradicts the file extension");
        }
        return expected;
    }

    String getMediaType() {
        return mediaType;
    }

    String extensionWithDot() {
        return "." + extensions.iterator().next();
    }

    private boolean acceptsClientMediaType(String client) {
        if (mediaType.equals(client)) {
            return true;
        }
        if (signature == UploadedAttachmentContentSignature.TEXT
                && "text/plain".equals(client)) {
            return true;
        }
        return this == JPEG && "image/jpg".equals(client);
    }

    private static String extension(String displayName) {
        int lastDot = displayName.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == displayName.length() - 1) {
            throw invalid("uploaded attachment extension is required");
        }
        return displayName.substring(lastDot + 1).toLowerCase(Locale.ROOT);
    }

    private static String normalizeClientMediaType(String clientMediaType) {
        if (clientMediaType == null || clientMediaType.trim().isEmpty()) {
            return null;
        }
        String normalized = clientMediaType.trim().toLowerCase(Locale.ROOT);
        int parameter = normalized.indexOf(';');
        return parameter < 0 ? normalized : normalized.substring(0, parameter).trim();
    }

    private static boolean isExecutable(
            UploadedAttachmentContentSignature signature) {
        return signature == UploadedAttachmentContentSignature.PE_EXECUTABLE
                || signature == UploadedAttachmentContentSignature.ELF_EXECUTABLE
                || signature == UploadedAttachmentContentSignature.MACHO_EXECUTABLE
                || signature == UploadedAttachmentContentSignature.SHEBANG_EXECUTABLE;
    }

    private static Map<String, UploadedAttachmentMediaType> byExtension() {
        Map<String, UploadedAttachmentMediaType> result =
                new HashMap<String, UploadedAttachmentMediaType>();
        for (UploadedAttachmentMediaType type : values()) {
            for (String extension : type.extensions) {
                if (result.put(extension, type) != null) {
                    throw new IllegalStateException(
                            "duplicate uploaded attachment extension");
                }
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static WorkbenchDomainException invalid(String message) {
        return new WorkbenchDomainException(
                WorkbenchErrorCode.ATTACHMENT_INVALID, message);
    }
}
