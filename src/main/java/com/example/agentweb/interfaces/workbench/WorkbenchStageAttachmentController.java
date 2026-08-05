package com.example.agentweb.interfaces.workbench;

import com.example.agentweb.app.workbench.attachment.UploadWorkbenchStageConversationAttachmentCommand;
import com.example.agentweb.app.workbench.attachment.UploadedAttachmentStorageException;
import com.example.agentweb.app.workbench.attachment.WorkbenchStageUploadedConversationAttachmentAppService;
import com.example.agentweb.app.workbench.attachment.WorkbenchStageUploadedConversationAttachmentView;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchId;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Dynamic Stage 浏览器上传附件的 Owner-scoped multipart 边界。
 *
 * @author alex
 * @since 2026-08-05
 */
@RestController
@RequestMapping(path = "/api/workbenches/{workbenchId}/stages/{stageInstanceIdentifier}",
        produces = MediaType.APPLICATION_JSON_VALUE)
public class WorkbenchStageAttachmentController {

    private final WorkbenchStageUploadedConversationAttachmentAppService
            appService;
    private final CurrentUserProvider currentUserProvider;

    public WorkbenchStageAttachmentController(
            WorkbenchStageUploadedConversationAttachmentAppService appService,
            CurrentUserProvider currentUserProvider) {
        this.appService = appService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping(path = "/attachments",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<WorkbenchStageUploadedConversationAttachmentView>
            upload(
                    @PathVariable("workbenchId") String workbenchId,
                    @PathVariable("stageInstanceIdentifier")
                    String stageInstanceIdentifier,
                    @RequestParam("conversationGeneration") int generation,
                    @RequestPart("file") MultipartFile file) {
        if (generation < 0 || file == null) {
            throw new IllegalArgumentException(
                    "Stage uploaded attachment request is invalid");
        }
        WorkbenchId identifier = WorkbenchId.of(workbenchId);
        try (InputStream input = file.getInputStream()) {
            WorkbenchStageUploadedConversationAttachmentView result =
                    appService.upload(
                            currentOwner(),
                            new UploadWorkbenchStageConversationAttachmentCommand(
                                    identifier, stageInstanceIdentifier,
                                    generation, file.getOriginalFilename(),
                                    file.getContentType(), file.getSize()),
                            input);
            return ResponseEntity.created(URI.create(
                            "/api/workbenches/" + identifier.getValue()
                                    + "/stages/" + stageInstanceIdentifier
                                    + "/attachments/"
                                    + result.getAttachmentId()))
                    .body(result);
        } catch (IOException failure) {
            throw new UploadedAttachmentStorageException(
                    "Stage uploaded attachment body could not be read");
        }
    }

    @DeleteMapping(path = "/attachments/{attachmentId}")
    public ResponseEntity<Void> cancel(
            @PathVariable("workbenchId") String workbenchId,
            @PathVariable("stageInstanceIdentifier")
            String stageInstanceIdentifier,
            @PathVariable("attachmentId") String attachmentId,
            @RequestParam("conversationGeneration") int generation) {
        if (generation < 0) {
            throw new IllegalArgumentException(
                    "Stage uploaded attachment request is invalid");
        }
        appService.cancel(
                currentOwner(), WorkbenchId.of(workbenchId),
                stageInstanceIdentifier, generation, attachmentId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidRequest(
            IllegalArgumentException ignoredFailure) {
        Map<String, Object> body = new HashMap<String, Object>(2);
        body.put("code", "WORKBENCH_REQUEST_INVALID");
        body.put("message", "workbench request is invalid");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    private OwnerReference currentOwner() {
        return OwnerReference.of(
                currentUserProvider.currentUserId(),
                currentUserProvider.currentUserName());
    }
}
