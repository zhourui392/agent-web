package com.example.agentweb.config.workbench;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Workbench 浏览器上传附件的生产限额和受控根配置。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
@ConfigurationProperties(prefix = "agent.workbench.attachment")
@Getter
@Setter
public class WorkbenchAttachmentProperties {

    private String storageRoot = "data/workbench/uploads";
    private long maximumBytes = 10L * 1024L * 1024L;
    private int maximumAvailablePerConversation = 16;
    private long availableTtlSeconds = 24L * 60L * 60L;
    private long boundRetentionSeconds = 2L * 60L * 60L;
    private int cleanupBatchSize = 100;

    @PostConstruct
    public void validate() {
        if (storageRoot == null || storageRoot.trim().isEmpty()
                || maximumBytes < 1L || maximumBytes > Integer.MAX_VALUE
                || maximumAvailablePerConversation < 1
                || availableTtlSeconds < 1L || boundRetentionSeconds < 1L
                || cleanupBatchSize < 1 || cleanupBatchSize > 1000) {
            throw new IllegalStateException(
                    "Invalid Workbench attachment configuration");
        }
    }
}
