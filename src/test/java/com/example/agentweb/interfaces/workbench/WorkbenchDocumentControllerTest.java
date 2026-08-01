package com.example.agentweb.interfaces.workbench;

import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.app.workbench.document.DocumentContentView;
import com.example.agentweb.app.workbench.document.DocumentDirectoryEntryView;
import com.example.agentweb.app.workbench.document.DocumentDirectoryQuery;
import com.example.agentweb.app.workbench.document.DocumentDirectoryView;
import com.example.agentweb.app.workbench.document.DocumentDownloadView;
import com.example.agentweb.app.workbench.document.DocumentEntryKind;
import com.example.agentweb.app.workbench.document.DocumentFailureCode;
import com.example.agentweb.app.workbench.document.DocumentKind;
import com.example.agentweb.app.workbench.document.DocumentOperationException;
import com.example.agentweb.app.workbench.document.WorkbenchDocumentAppService;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.interfaces.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Scoped Document query 路由、Owner、ETag 与下载安全头契约测试。
 *
 * @author alex
 * @since 2026-08-01
 */
@WebMvcTest(WorkbenchDocumentController.class)
@Import({GlobalExceptionHandler.class, WorkbenchExceptionHandler.class})
class WorkbenchDocumentControllerTest {

    private static final String OWNER_ID = "owner-1";
    private static final String OWNER_NAME = "Alex";
    private static final String WORKBENCH_ID = "workbench-1";
    private static final String BASE = "/api/workbenches/{workbenchId}/documents";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private WorkbenchDocumentAppService appService;

    @MockBean
    private CurrentUserProvider currentUserProvider;

    @BeforeEach
    void authenticateOwner() {
        when(currentUserProvider.currentUserId()).thenReturn(OWNER_ID);
        when(currentUserProvider.currentUserName()).thenReturn(OWNER_NAME);
    }

    @Test
    void treeShouldUseQueryParametersSoLogicalRepositoryKeyMayContainSlash()
            throws Exception {
        DocumentDirectoryView view = new DocumentDirectoryView(
                "service/api", "docs",
                Collections.singletonList(new DocumentDirectoryEntryView(
                        "README.md", "docs/README.md", DocumentEntryKind.FILE,
                        Long.valueOf(12L), 1000L)), false);
        when(appService.listTree(any(), any(), any())).thenReturn(view);

        mvc.perform(get(BASE + "/tree", WORKBENCH_ID)
                        .param("repositoryKey", "service/api")
                        .param("path", "docs")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositoryKey").value("service/api"))
                .andExpect(jsonPath("$.path").value("docs"))
                .andExpect(jsonPath("$.entries[0].relativePath")
                        .value("docs/README.md"))
                .andExpect(jsonPath("$.entries[0].kind").value("FILE"))
                .andExpect(jsonPath("$.entries[0].size").value(12))
                .andExpect(jsonPath("$.truncated").value(false));

        ArgumentCaptor<OwnerReference> owner =
                ArgumentCaptor.forClass(OwnerReference.class);
        ArgumentCaptor<WorkbenchId> workbenchId =
                ArgumentCaptor.forClass(WorkbenchId.class);
        ArgumentCaptor<DocumentDirectoryQuery> query =
                ArgumentCaptor.forClass(DocumentDirectoryQuery.class);
        verify(appService).listTree(
                owner.capture(), workbenchId.capture(), query.capture());
        assertEquals(OwnerReference.of(OWNER_ID, OWNER_NAME), owner.getValue());
        assertEquals(WorkbenchId.of(WORKBENCH_ID), workbenchId.getValue());
        assertEquals("service/api", query.getValue().getRepositoryKey());
        assertEquals("docs", query.getValue().getRelativePath());
        assertEquals(50, query.getValue().getLimit());
    }

    @Test
    void contentEtagShouldHashExactJsonResponseBytesAndSupport304() throws Exception {
        DocumentContentView view = contentView();
        when(appService.readContent(any(), any(), any())).thenReturn(view);

        MvcResult loaded = mvc.perform(get(BASE + "/content", WORKBENCH_ID)
                        .param("repositoryKey", "service/api")
                        .param("path", "docs/README.md"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        "private, no-cache"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.reference.repositoryKey").value("service/api"))
                .andExpect(jsonPath("$.reference.relativePath")
                        .value("docs/README.md"))
                .andExpect(jsonPath("$.kind").value("MARKDOWN"))
                .andExpect(jsonPath("$.content").value("# Design"))
                .andExpect(content().string(not(containsString("/workspace/service-api"))))
                .andReturn();
        byte[] body = loaded.getResponse().getContentAsByteArray();
        String etag = "\"" + CanonicalHashing.sha256(body) + "\"";
        assertEquals(etag, loaded.getResponse().getHeader(HttpHeaders.ETAG));

        mvc.perform(get(BASE + "/content", WORKBENCH_ID)
                        .param("repositoryKey", "service/api")
                        .param("path", "docs/README.md")
                        .header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, etag))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        "private, no-cache"))
                .andExpect(content().bytes(new byte[0]));
    }

    @Test
    void downloadShouldReturnFrozenBytesAttachmentAndSourceEtag() throws Exception {
        byte[] body = "download-body".getBytes(StandardCharsets.UTF_8);
        DocumentReference reference = DocumentReference.of(
                "service/api", "docs/设计.md");
        DocumentDownloadView view = new DocumentDownloadView(
                reference, "设计.md", "text/markdown", 1000L,
                CanonicalHashing.sha256(body), body);
        when(appService.download(any(), any(), any())).thenReturn(view);

        mvc.perform(get(BASE + "/download", WORKBENCH_ID)
                        .param("repositoryKey", "service/api")
                        .param("path", "docs/设计.md"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("attachment")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("filename*=UTF-8''")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.ETAG,
                        "\"" + CanonicalHashing.sha256(body) + "\""))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, body.length))
                .andExpect(content().contentType("text/markdown"))
                .andExpect(content().bytes(body));
    }

    @Test
    void inlineImageShouldRemainOwnerScopedAndReturnHardenedImageResponse()
            throws Exception {
        byte[] body = new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        String contentVersion = CanonicalHashing.sha256(body);
        DocumentDownloadView view = new DocumentDownloadView(
                DocumentReference.of("service/api", "docs/diagram.png"),
                "diagram.png", "image/png", 1000L, contentVersion, body);
        when(appService.inlineImage(any(), any(), any())).thenReturn(view);

        MvcResult loaded = mvc.perform(get(BASE + "/inline-image", WORKBENCH_ID)
                        .param("repositoryKey", "service/api")
                        .param("path", "docs/diagram.png"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        "private, no-cache"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy",
                        "default-src 'none'; sandbox"))
                .andExpect(header().string("Cross-Origin-Resource-Policy",
                        "same-origin"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("inline")))
                .andExpect(header().string(HttpHeaders.ETAG,
                        "\"" + contentVersion + "\""))
                .andExpect(header().longValue(
                        HttpHeaders.CONTENT_LENGTH, body.length))
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(body))
                .andReturn();

        String etag = loaded.getResponse().getHeader(HttpHeaders.ETAG);
        mvc.perform(get(BASE + "/inline-image", WORKBENCH_ID)
                        .param("repositoryKey", "service/api")
                        .param("path", "docs/diagram.png")
                        .header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, etag))
                .andExpect(header().string("Content-Security-Policy",
                        "default-src 'none'; sandbox"))
                .andExpect(content().bytes(new byte[0]));

        ArgumentCaptor<DocumentReference> reference =
                ArgumentCaptor.forClass(DocumentReference.class);
        verify(appService, org.mockito.Mockito.times(2)).inlineImage(
                any(), any(), reference.capture());
        assertEquals(DocumentReference.of(
                "service/api", "docs/diagram.png"),
                reference.getAllValues().get(0));
    }

    @Test
    void invalidTreeAndContentInputsShouldReturn400BeforeApplicationCall()
            throws Exception {
        mvc.perform(get(BASE + "/tree", WORKBENCH_ID)
                        .param("repositoryKey", "service/api")
                        .param("path", "../secret")
                        .param("limit", "1001"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/content", WORKBENCH_ID)
                        .param("repositoryKey", "service/api")
                        .param("path", ""))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/inline-image", WORKBENCH_ID)
                        .param("repositoryKey", "service/api")
                        .param("path", "../secret.png"))
                .andExpect(status().isBadRequest());

        verify(appService, never()).listTree(any(), any(), any());
        verify(appService, never()).readContent(any(), any(), any());
        verify(appService, never()).inlineImage(any(), any(), any());
    }

    @Test
    void documentFailuresShouldUseStableStatusAndCodeMapping() throws Exception {
        when(appService.readContent(any(), any(), any()))
                .thenThrow(new DocumentOperationException(
                        DocumentFailureCode.WORKBENCH_DOCUMENT_NOT_FOUND,
                        "document not found"))
                .thenThrow(new DocumentOperationException(
                        DocumentFailureCode.WORKBENCH_DOCUMENT_CHANGED_DURING_READ,
                        "document changed"))
                .thenThrow(new DocumentOperationException(
                        DocumentFailureCode.WORKBENCH_DOCUMENT_TOO_LARGE,
                        "document too large"));

        requestContent().andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("WORKBENCH_DOCUMENT_NOT_FOUND"));
        requestContent().andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("WORKBENCH_DOCUMENT_CHANGED_DURING_READ"));
        requestContent().andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code")
                        .value("WORKBENCH_DOCUMENT_TOO_LARGE"));
    }

    @Test
    void missingOrForeignWorkbenchShouldKeepOwner404Contract() throws Exception {
        when(appService.readContent(any(), any(), any()))
                .thenThrow(new WorkbenchNotFoundException());

        requestContent().andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKBENCH_NOT_FOUND"));
    }

    private org.springframework.test.web.servlet.ResultActions requestContent()
            throws Exception {
        return mvc.perform(get(BASE + "/content", WORKBENCH_ID)
                .param("repositoryKey", "service/api")
                .param("path", "docs/README.md"));
    }

    private DocumentContentView contentView() {
        return new DocumentContentView(
                DocumentReference.of("service/api", "docs/README.md"),
                DocumentKind.MARKDOWN, "text/markdown", "UTF-8",
                8L, 1000L, CanonicalHashing.sha256("# Design"),
                "# Design", false, false);
    }
}
