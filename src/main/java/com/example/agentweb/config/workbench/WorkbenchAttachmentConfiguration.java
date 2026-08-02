package com.example.agentweb.config.workbench;

import com.example.agentweb.app.workbench.attachment.port.UploadedConversationAttachmentStorage;
import com.example.agentweb.app.workbench.attachment.UploadedAttachmentCleanupSettings;
import com.example.agentweb.domain.workbench.UploadedAttachmentPolicy;
import com.example.agentweb.infra.workbench.FileSystemUploadedConversationAttachmentStorage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;
import java.time.Duration;

/**
 * Workbench 上传附件领域策略与受控临时存储装配。
 *
 * @author alex
 * @since 2026-08-01
 */
@Configuration(proxyBeanMethods = false)
public class WorkbenchAttachmentConfiguration {

    @Bean
    public UploadedAttachmentCleanupSettings uploadedAttachmentCleanupSettings(
            WorkbenchAttachmentProperties properties) {
        properties.validate();
        return new UploadedAttachmentCleanupSettings(
                properties.getCleanupBatchSize());
    }

    @Bean
    public UploadedAttachmentPolicy uploadedAttachmentPolicy(
            WorkbenchAttachmentProperties properties) {
        properties.validate();
        return UploadedAttachmentPolicy.standard(
                properties.getMaximumBytes(),
                properties.getMaximumAvailablePerConversation(),
                Duration.ofSeconds(properties.getAvailableTtlSeconds()),
                Duration.ofSeconds(properties.getBoundRetentionSeconds()));
    }

    @Bean
    public UploadedConversationAttachmentStorage
            uploadedConversationAttachmentStorage(
            WorkbenchAttachmentProperties properties) {
        properties.validate();
        return new FileSystemUploadedConversationAttachmentStorage(
                Paths.get(properties.getStorageRoot()),
                properties.getMaximumBytes());
    }
}
