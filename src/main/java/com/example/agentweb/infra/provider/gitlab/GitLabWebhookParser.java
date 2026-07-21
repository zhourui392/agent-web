package com.example.agentweb.infra.provider.gitlab;

import com.example.agentweb.adapter.delivery.ScmWebhookEvent;
import com.example.agentweb.adapter.delivery.WebhookEnvelope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * GitLab webhook JSON → 平台事件的防腐解析:按 X-Gitlab-Event 头分发四类,
 * 其余类型 / 解析失败一律 Unsupported(只 log.warn 不抛,webhook 回调不可因烂 payload 5xx)。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Slf4j
class GitLabWebhookParser {

    private static final String EVENT_PIPELINE = "Pipeline Hook";
    private static final String EVENT_NOTE = "Note Hook";
    private static final String EVENT_MERGE_REQUEST = "Merge Request Hook";
    private static final String EVENT_ISSUE = "Issue Hook";

    /** issue 接入 agent 开发流程的默认标签名(配置留空时的兜底) */
    static final String DEFAULT_INTAKE_LABEL = "agent-dev";

    private static final int NOTE_EXCERPT_MAX = 200;

    private final ObjectMapper objectMapper;
    private final String intakeLabel;

    GitLabWebhookParser(ObjectMapper objectMapper, String intakeLabel) {
        this.objectMapper = objectMapper;
        this.intakeLabel = intakeLabel == null || intakeLabel.trim().isEmpty()
                ? DEFAULT_INTAKE_LABEL
                : intakeLabel.trim();
    }

    /**
     * 解析 webhook 信封为平台事件。
     *
     * @param envelope 原始信封(eventType + rawBody)
     * @return 平台事件;不认识/解析失败返回 Unsupported
     */
    ScmWebhookEvent parse(WebhookEnvelope envelope) {
        String eventType = envelope.getEventType() == null ? "" : envelope.getEventType();
        try {
            JsonNode root = objectMapper.readTree(envelope.getRawBody());
            return switch (eventType) {
                case EVENT_PIPELINE -> parsePipeline(root, eventType);
                case EVENT_NOTE -> parseNote(root, eventType);
                case EVENT_MERGE_REQUEST -> parseMergeRequest(root, eventType);
                case EVENT_ISSUE -> parseIssue(root, eventType);
                default -> new ScmWebhookEvent.Unsupported(eventType);
            };
        } catch (JsonProcessingException e) {
            log.warn("gitlab-webhook-parse-failed eventType={} reason={}", eventType, e.getMessage());
            return new ScmWebhookEvent.Unsupported(eventType);
        }
    }

    private ScmWebhookEvent parsePipeline(JsonNode root, String eventType) {
        JsonNode attrs = root.path("object_attributes");
        String status = text(attrs, "status");
        if (!"failed".equals(status)) {
            return new ScmWebhookEvent.Unsupported(eventType);
        }
        return new ScmWebhookEvent.PipelineFailed(text(attrs, "ref"), pipelineUrl(root, attrs), status);
    }

    /** object_attributes.url 缺失时用 project.web_url 拼 /-/pipelines/{id} 兜底 */
    private String pipelineUrl(JsonNode root, JsonNode attrs) {
        String url = text(attrs, "url");
        if (url != null) {
            return url;
        }
        String projectWebUrl = text(root.path("project"), "web_url");
        if (projectWebUrl == null) {
            return null;
        }
        return projectWebUrl + "/-/pipelines/" + attrs.path("id").asLong();
    }

    private ScmWebhookEvent parseNote(JsonNode root, String eventType) {
        JsonNode mergeRequest = root.path("merge_request");
        if (mergeRequest.isMissingNode() || mergeRequest.isNull()) {
            return new ScmWebhookEvent.Unsupported(eventType);
        }
        JsonNode attrs = root.path("object_attributes");
        return new ScmWebhookEvent.MrNoteAdded(
                mergeRequest.path("iid").asLong(),
                text(mergeRequest, "source_branch"),
                text(root.path("user"), "username"),
                excerpt(text(attrs, "note")),
                text(attrs, "url"));
    }

    private ScmWebhookEvent parseMergeRequest(JsonNode root, String eventType) {
        JsonNode attrs = root.path("object_attributes");
        boolean isMerged = "merge".equals(text(attrs, "action")) || "merged".equals(text(attrs, "state"));
        if (!isMerged) {
            return new ScmWebhookEvent.Unsupported(eventType);
        }
        return new ScmWebhookEvent.MrMerged(
                attrs.path("iid").asLong(),
                text(attrs, "source_branch"),
                text(root.path("user"), "username"));
    }

    private ScmWebhookEvent parseIssue(JsonNode root, String eventType) {
        JsonNode attrs = root.path("object_attributes");
        String action = text(attrs, "action");
        boolean actionAccepted = "open".equals(action) || "update".equals(action);
        List<String> labelTitles = labelTitles(root, attrs);
        if (!actionAccepted || !labelTitles.contains(intakeLabel)) {
            return new ScmWebhookEvent.Unsupported(eventType);
        }
        return new ScmWebhookEvent.IssueLabeled(
                text(attrs, "url"),
                text(attrs, "title"),
                text(attrs, "description"),
                text(root.path("user"), "username"),
                labelTitles);
    }

    /** 标签取顶层 labels 数组,缺失时回落 object_attributes.labels */
    private List<String> labelTitles(JsonNode root, JsonNode attrs) {
        JsonNode labels = root.path("labels");
        if (!labels.isArray()) {
            labels = attrs.path("labels");
        }
        List<String> titles = new ArrayList<>();
        if (labels.isArray()) {
            for (JsonNode label : labels) {
                String title = text(label, "title");
                if (title != null) {
                    titles.add(title);
                }
            }
        }
        return titles;
    }

    private String excerpt(String note) {
        if (note == null) {
            return null;
        }
        return note.length() <= NOTE_EXCERPT_MAX ? note : note.substring(0, NOTE_EXCERPT_MAX);
    }

    private String text(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
