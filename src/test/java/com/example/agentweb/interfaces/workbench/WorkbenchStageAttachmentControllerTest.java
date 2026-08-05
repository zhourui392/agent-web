package com.example.agentweb.interfaces.workbench;

import com.example.agentweb.app.workbench.attachment.UploadWorkbenchStageConversationAttachmentCommand;
import com.example.agentweb.app.workbench.attachment.WorkbenchStageUploadedConversationAttachmentAppService;
import com.example.agentweb.app.workbench.attachment.WorkbenchStageUploadedConversationAttachmentView;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.UploadedAttachmentContentSignature;
import com.example.agentweb.domain.workbench.UploadedAttachmentPolicy;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedAttachmentBinding;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedConversationAttachment;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dynamic Stage multipart 附件接口的身份与安全投影测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class WorkbenchStageAttachmentControllerTest {

    private WorkbenchStageUploadedConversationAttachmentAppService appService;
    private CurrentUserProvider currentUserProvider;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        appService = mock(
                WorkbenchStageUploadedConversationAttachmentAppService.class);
        currentUserProvider = mock(CurrentUserProvider.class);
        when(currentUserProvider.currentUserId()).thenReturn("owner-1");
        when(currentUserProvider.currentUserName()).thenReturn("Alex");
        mvc = MockMvcBuilders.standaloneSetup(
                        new WorkbenchStageAttachmentController(
                                appService, currentUserProvider))
                .setControllerAdvice(new WorkbenchExceptionHandler())
                .build();
    }

    @Test
    void should_UploadAgainstStageIdentityAndReturnOnlyLogicalFacts()
            throws Exception {
        // Given
        WorkbenchStageUploadedConversationAttachment attachment =
                stageAttachment();
        when(appService.upload(any(), any(), any())).thenReturn(
                WorkbenchStageUploadedConversationAttachmentView.from(
                        attachment));
        MockMultipartFile file = new MockMultipartFile(
                "file", "design.md", "text/markdown",
                "# Design".getBytes(
                        java.nio.charset.StandardCharsets.UTF_8));

        // When / Then
        mvc.perform(multipart(
                        "/api/workbenches/workbench-1/stages/stage-design/attachments")
                        .file(file)
                        .param("conversationGeneration", "3"))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location",
                        "/api/workbenches/workbench-1/stages/stage-design/attachments/stage-attachment-1"))
                .andExpect(jsonPath("$.attachmentId")
                        .value("stage-attachment-1"))
                .andExpect(jsonPath("$.displayName").value("design.md"))
                .andExpect(jsonPath("$.sha256").value(repeat('a')))
                .andExpect(jsonPath("$.storageKey").doesNotExist())
                .andExpect(jsonPath("$.path").doesNotExist())
                .andExpect(jsonPath("$.phase").doesNotExist());

        ArgumentCaptor<OwnerReference> owner =
                ArgumentCaptor.forClass(OwnerReference.class);
        ArgumentCaptor<UploadWorkbenchStageConversationAttachmentCommand>
                command = ArgumentCaptor.forClass(
                UploadWorkbenchStageConversationAttachmentCommand.class);
        verify(appService).upload(
                owner.capture(), command.capture(), any());
        assertEquals("owner-1", owner.getValue().getOwnerId());
        assertEquals("stage-design",
                command.getValue().getStageInstanceIdentifier());
        assertEquals(3, command.getValue().getConversationGeneration());
    }

    @Test
    void should_RejectInvalidGenerationAndHideUnreadableMultipartPath()
            throws Exception {
        // Given
        MockMultipartFile readable = new MockMultipartFile(
                "file", "design.md", MediaType.TEXT_PLAIN_VALUE,
                new byte[] {1});
        MockMultipartFile unreadable = new MockMultipartFile(
                "file", "design.md", MediaType.TEXT_PLAIN_VALUE,
                new byte[] {1}) {
            @Override
            public java.io.InputStream getInputStream()
                    throws java.io.IOException {
                throw new java.io.IOException("/private/upload/location");
            }
        };

        // When / Then
        mvc.perform(multipart(
                        "/api/workbenches/workbench-1/stages/stage-design/attachments")
                        .file(readable)
                        .param("conversationGeneration", "-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart(
                        "/api/workbenches/workbench-1/stages/stage-design/attachments")
                        .file(unreadable)
                        .param("conversationGeneration", "0"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(
                        "WORKBENCH_ATTACHMENT_STORAGE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message",
                        not(containsString("/private/"))));
    }

    @Test
    void should_CancelOnlyCurrentStageConversationAttachment()
            throws Exception {
        // When
        mvc.perform(delete(
                        "/api/workbenches/workbench-1/stages/stage-design/attachments/stage-attachment-1")
                        .param("conversationGeneration", "3"))
                .andExpect(status().isNoContent());

        // Then
        verify(appService).cancel(
                OwnerReference.of("owner-1", "Alex"),
                WorkbenchId.of("workbench-1"), "stage-design",
                3, "stage-attachment-1");
    }

    private WorkbenchStageUploadedConversationAttachment stageAttachment() {
        return WorkbenchStageUploadedConversationAttachment.upload(
                "stage-attachment-1",
                new WorkbenchStageUploadedAttachmentBinding(
                        OwnerReference.of("owner-1", "Alex"),
                        WorkbenchId.of("workbench-1"), "stage-design",
                        "stage-session-1", 3),
                "design.md", "text/markdown",
                UploadedAttachmentContentSignature.TEXT,
                8L, repeat('a'), repeat('b'),
                UploadedAttachmentPolicy.standard(
                        1024L, 8, Duration.ofHours(1),
                        Duration.ofHours(2)),
                Instant.parse("2026-08-05T15:00:00Z"));
    }

    private String repeat(char value) {
        return String.join("", Collections.nCopies(
                64, Character.toString(value)));
    }
}
