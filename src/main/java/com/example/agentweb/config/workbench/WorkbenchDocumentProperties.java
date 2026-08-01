package com.example.agentweb.config.workbench;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Workbench Scoped Document 的目录、预览、图片和下载技术限额。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
@ConfigurationProperties(prefix = "agent.workbench.document")
@Getter
@Setter
public class WorkbenchDocumentProperties {

    private int maxDirectoryEntries = 1000;
    private long maxTextBytes = 2L * 1024L * 1024L;
    private long maxLogPreviewBytes = 2L * 1024L * 1024L;
    private long maxImageBytes = 10L * 1024L * 1024L;
    private long maxDownloadBytes = 50L * 1024L * 1024L;

    @PostConstruct
    public void validate() {
        if (maxDirectoryEntries < 1
                || maxDirectoryEntries > DocumentDirectoryLimit.MAXIMUM) {
            throw invalid("max-directory-entries must be between 1 and 1000");
        }
        requireBoundedBytes(maxTextBytes, "max-text-bytes");
        requireBoundedBytes(maxLogPreviewBytes, "max-log-preview-bytes");
        requireBoundedBytes(maxImageBytes, "max-image-bytes");
        requireBoundedBytes(maxDownloadBytes, "max-download-bytes");
        if (maxTextBytes > maxDownloadBytes
                || maxLogPreviewBytes > maxDownloadBytes
                || maxImageBytes > maxDownloadBytes) {
            throw invalid("preview and image limits must not exceed max-download-bytes");
        }
    }

    private void requireBoundedBytes(long value, String name) {
        if (value < 1L || value > Integer.MAX_VALUE) {
            throw invalid(name + " must be between 1 and Integer.MAX_VALUE");
        }
    }

    private IllegalStateException invalid(String message) {
        return new IllegalStateException(
                "Invalid Workbench document configuration: " + message);
    }

    private static final class DocumentDirectoryLimit {

        private static final int MAXIMUM = 1000;

        private DocumentDirectoryLimit() {
        }
    }
}
