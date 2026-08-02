package com.example.agentweb.interfaces.workbench;

import com.example.agentweb.app.workbench.attachment.UploadConversationAttachmentCommand;
import com.example.agentweb.app.workbench.attachment.UploadedConversationAttachmentAppService;
import com.example.agentweb.app.workbench.attachment.UploadedConversationAttachmentView;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.UploadedAttachmentBinding;
import com.example.agentweb.domain.workbench.UploadedAttachmentContentSignature;
import com.example.agentweb.domain.workbench.UploadedAttachmentPolicy;
import com.example.agentweb.domain.workbench.UploadedConversationAttachment;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Workbench multipart 上传 API 的 Owner、代际与安全投影合同测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkbenchAttachmentControllerTest {

    private UploadedConversationAttachmentAppService appService;
    private CurrentUserProvider currentUserProvider;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        appService = mock(UploadedConversationAttachmentAppService.class);
        currentUserProvider = mock(CurrentUserProvider.class);
        when(currentUserProvider.currentUserId()).thenReturn("owner-1");
        when(currentUserProvider.currentUserName()).thenReturn("Alex");
        mvc = MockMvcBuilders.standaloneSetup(
                        new WorkbenchAttachmentController(
                                appService, currentUserProvider))
                .setControllerAdvice(new WorkbenchExceptionHandler())
                .build();
    }

    @Test
    void multipartUploadShouldBindCurrentOwnerAndReturnOnlyLogicalFacts()
            throws Exception {
        UploadedConversationAttachment attachment = UploadedConversationAttachment.upload(
                "attachment-1",
                new UploadedAttachmentBinding(
                        OwnerReference.of("owner-1", "Alex"),
                        WorkbenchId.of("workbench-1"),
                        WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        "conversation-1", 3),
                "design.md", "text/markdown",
                UploadedAttachmentContentSignature.TEXT,
                8L, repeat('a'), repeat('b'),
                UploadedAttachmentPolicy.standard(
                        1024L, 8, Duration.ofHours(1), Duration.ofHours(2)),
                Instant.parse("2026-08-01T00:00:00Z"));
        when(appService.upload(any(), any(), any()))
                .thenReturn(UploadedConversationAttachmentView.from(attachment));
        MockMultipartFile file = new MockMultipartFile(
                "file", "design.md", "text/markdown",
                "# Design".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mvc.perform(multipart(
                        "/api/workbenches/workbench-1/phases/requirement_analysis/attachments")
                        .file(file)
                        .param("conversationGeneration", "3"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        "/api/workbenches/workbench-1/phases/REQUIREMENT_ANALYSIS/attachments/attachment-1"))
                .andExpect(jsonPath("$.attachmentId").value("attachment-1"))
                .andExpect(jsonPath("$.displayName").value("design.md"))
                .andExpect(jsonPath("$.mediaType").value("text/markdown"))
                .andExpect(jsonPath("$.size").value(8))
                .andExpect(jsonPath("$.sha256").value(repeat('a')))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.storageKey").doesNotExist())
                .andExpect(jsonPath("$.path").doesNotExist());

        ArgumentCaptor<OwnerReference> owner =
                ArgumentCaptor.forClass(OwnerReference.class);
        ArgumentCaptor<UploadConversationAttachmentCommand> command =
                ArgumentCaptor.forClass(UploadConversationAttachmentCommand.class);
        org.mockito.Mockito.verify(appService).upload(
                owner.capture(), command.capture(), any());
        assertEquals("owner-1", owner.getValue().getOwnerId());
        assertEquals(3, command.getValue().getConversationGeneration());
        assertEquals("design.md", command.getValue().getDisplayName());
    }

    private String repeat(char value) {
        return String.join("", Collections.nCopies(64,
                Character.toString(value)));
    }

    @Test
    void missingFileOrNegativeGenerationShouldBeBadRequestWithoutPathLeak()
            throws Exception {
        mvc.perform(multipart(
                        "/api/workbenches/workbench-1/phases/REQUIREMENT_ANALYSIS/attachments")
                        .param("conversationGeneration", "0"))
                .andExpect(status().isBadRequest());

        MockMultipartFile file = new MockMultipartFile(
                "file", "design.md", MediaType.TEXT_PLAIN_VALUE,
                "x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(multipart(
                        "/api/workbenches/workbench-1/phases/REQUIREMENT_ANALYSIS/attachments")
                        .file(file).param("conversationGeneration", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", not(containsString("/home/"))));
    }

    @Test
    void unreadableMultipartAndUnknownPhaseShouldUseStableSafeErrors()
            throws Exception {
        MockMultipartFile unreadable = new MockMultipartFile(
                "file", "design.md", MediaType.TEXT_PLAIN_VALUE,
                new byte[] {1}) {
            @Override
            public java.io.InputStream getInputStream()
                    throws java.io.IOException {
                throw new java.io.IOException("/private/upload/location");
            }
        };

        mvc.perform(multipart(
                        "/api/workbenches/workbench-1/phases/REQUIREMENT_ANALYSIS/attachments")
                        .file(unreadable).param("conversationGeneration", "0"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(
                        "WORKBENCH_ATTACHMENT_STORAGE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message",
                        not(containsString("/private/"))));

        MockMultipartFile readable = new MockMultipartFile(
                "file", "design.md", MediaType.TEXT_PLAIN_VALUE,
                new byte[] {1});
        mvc.perform(multipart(
                        "/api/workbenches/workbench-1/phases/unknown/attachments")
                        .file(readable).param("conversationGeneration", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(
                        "WORKBENCH_REQUEST_INVALID"));
    }

    @Test
    void deleteShouldCancelOnlyCurrentOwnerScopedConversationAttachment()
            throws Exception {
        mvc.perform(delete(
                        "/api/workbenches/workbench-1/phases/REQUIREMENT_ANALYSIS/attachments/attachment-1")
                        .param("conversationGeneration", "3"))
                .andExpect(status().isNoContent());

        org.mockito.Mockito.verify(appService).cancel(
                OwnerReference.of("owner-1", "Alex"),
                WorkbenchId.of("workbench-1"),
                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                3, "attachment-1");
    }
}
