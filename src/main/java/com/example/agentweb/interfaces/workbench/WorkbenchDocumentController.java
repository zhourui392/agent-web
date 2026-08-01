package com.example.agentweb.interfaces.workbench;

import com.example.agentweb.app.workbench.document.DocumentContentView;
import com.example.agentweb.app.workbench.document.DocumentDirectoryQuery;
import com.example.agentweb.app.workbench.document.DocumentDirectoryView;
import com.example.agentweb.app.workbench.document.DocumentDownloadView;
import com.example.agentweb.app.workbench.document.WorkbenchDocumentAppService;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Workbench Owner Scope 内 tree/content/download 的只读 HTTP 边界。
 *
 * @author alex
 * @since 2026-08-01
 */
@RestController
@RequestMapping("/api/workbenches/{workbenchId}/documents")
public class WorkbenchDocumentController {

    private static final int DEFAULT_TREE_LIMIT = 1000;
    private static final String CACHE_CONTROL = "private, no-cache";
    private static final String NOSNIFF = "X-Content-Type-Options";
    private static final String CONTENT_SECURITY_POLICY =
            "Content-Security-Policy";
    private static final String CROSS_ORIGIN_RESOURCE_POLICY =
            "Cross-Origin-Resource-Policy";
    private static final String INLINE_IMAGE_CSP =
            "default-src 'none'; sandbox";

    private final WorkbenchDocumentAppService appService;
    private final CurrentUserProvider currentUserProvider;
    private final ObjectMapper objectMapper;

    public WorkbenchDocumentController(
            WorkbenchDocumentAppService appService,
            CurrentUserProvider currentUserProvider,
            ObjectMapper objectMapper) {
        this.appService = appService;
        this.currentUserProvider = currentUserProvider;
        this.objectMapper = objectMapper;
    }

    @GetMapping(path = "/tree", produces = MediaType.APPLICATION_JSON_VALUE)
    public DocumentDirectoryView tree(
            @PathVariable("workbenchId") String workbenchId,
            @RequestParam("repositoryKey") String repositoryKey,
            @RequestParam(value = "path", defaultValue = "") String relativePath,
            @RequestParam(value = "limit", defaultValue = "" + DEFAULT_TREE_LIMIT)
                    int limit) {
        return appService.listTree(
                currentOwner(), WorkbenchId.of(workbenchId),
                new DocumentDirectoryQuery(repositoryKey, relativePath, limit));
    }

    @GetMapping(path = "/content", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> content(
            @PathVariable("workbenchId") String workbenchId,
            @RequestParam("repositoryKey") String repositoryKey,
            @RequestParam("path") String relativePath,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false)
                    String ifNoneMatch) {
        DocumentContentView view = appService.readContent(
                currentOwner(), WorkbenchId.of(workbenchId),
                DocumentReference.of(repositoryKey, relativePath));
        byte[] body = jsonBytes(view);
        String etag = quote(sha256(body));
        HttpHeaders headers = documentHeaders(etag);
        if (matches(ifNoneMatch, etag)) {
            return new ResponseEntity<byte[]>(null, headers, HttpStatus.NOT_MODIFIED);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setContentLength(body.length);
        return new ResponseEntity<byte[]>(body, headers, HttpStatus.OK);
    }

    @GetMapping("/download")
    public ResponseEntity<byte[]> download(
            @PathVariable("workbenchId") String workbenchId,
            @RequestParam("repositoryKey") String repositoryKey,
            @RequestParam("path") String relativePath,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false)
                    String ifNoneMatch) {
        DocumentDownloadView view = appService.download(
                currentOwner(), WorkbenchId.of(workbenchId),
                DocumentReference.of(repositoryKey, relativePath));
        String etag = quote(view.getContentVersion());
        HttpHeaders headers = documentHeaders(etag);
        if (matches(ifNoneMatch, etag)) {
            return new ResponseEntity<byte[]>(null, headers, HttpStatus.NOT_MODIFIED);
        }
        byte[] body = view.getContent();
        headers.setContentType(MediaType.parseMediaType(view.getMediaType()));
        headers.setContentLength(body.length);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(view.getFileName(), StandardCharsets.UTF_8)
                .build());
        return new ResponseEntity<byte[]>(body, headers, HttpStatus.OK);
    }

    @GetMapping("/inline-image")
    public ResponseEntity<byte[]> inlineImage(
            @PathVariable("workbenchId") String workbenchId,
            @RequestParam("repositoryKey") String repositoryKey,
            @RequestParam("path") String relativePath,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false)
                    String ifNoneMatch) {
        DocumentDownloadView view = appService.inlineImage(
                currentOwner(), WorkbenchId.of(workbenchId),
                DocumentReference.of(repositoryKey, relativePath));
        String etag = quote(view.getContentVersion());
        HttpHeaders headers = inlineImageHeaders(etag);
        if (matches(ifNoneMatch, etag)) {
            return new ResponseEntity<byte[]>(null, headers, HttpStatus.NOT_MODIFIED);
        }
        byte[] body = view.getContent();
        headers.setContentType(MediaType.parseMediaType(view.getMediaType()));
        headers.setContentLength(body.length);
        headers.setContentDisposition(ContentDisposition.inline()
                .filename(view.getFileName(), StandardCharsets.UTF_8)
                .build());
        return new ResponseEntity<byte[]>(body, headers, HttpStatus.OK);
    }

    private OwnerReference currentOwner() {
        return OwnerReference.of(
                currentUserProvider.currentUserId(),
                currentUserProvider.currentUserName());
    }

    private byte[] jsonBytes(DocumentContentView view) {
        try {
            return objectMapper.writeValueAsBytes(view);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "document response could not be serialized", ex);
        }
    }

    private HttpHeaders documentHeaders(String etag) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CACHE_CONTROL);
        headers.setETag(etag);
        headers.set(NOSNIFF, "nosniff");
        return headers;
    }

    private HttpHeaders inlineImageHeaders(String etag) {
        HttpHeaders headers = documentHeaders(etag);
        headers.set(CONTENT_SECURITY_POLICY, INLINE_IMAGE_CSP);
        headers.set(CROSS_ORIGIN_RESOURCE_POLICY, "same-origin");
        return headers;
    }

    private boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.trim().isEmpty()) {
            return false;
        }
        String[] candidates = ifNoneMatch.split(",");
        for (String candidate : candidates) {
            String value = candidate.trim();
            if ("*".equals(value) || etag.equals(value)
                    || ("W/" + etag).equals(value)) {
                return true;
            }
        }
        return false;
    }

    private String quote(String value) {
        return "\"" + value + "\"";
    }

    private String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte current : digest) {
                value.append(String.format("%02x", current & 0xff));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
