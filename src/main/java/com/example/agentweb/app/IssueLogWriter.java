package com.example.agentweb.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * 将对话摘要写入 issue-log 目录，维护 issue 编号和索引文件。
 */
@Component
class IssueLogWriter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 从 CLI 流式 JSON 输出中提取纯文本内容。
     *
     * @param rawOutput CLI 原始输出（可能包含 stream-json 格式）
     * @return 提取后的纯文本
     */
    String extractPlainText(String rawOutput) {
        StringBuilder plainText = new StringBuilder();
        for (String line : rawOutput.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("{")) {
                parseJsonLine(line, plainText);
            } else {
                plainText.append(line).append("\n");
            }
        }
        return plainText.toString().trim();
    }

    /**
     * 将摘要文本写入 issue-log 目录并更新索引。
     *
     * @param workingDir 工作目录
     * @param sessionId  会话 ID
     * @param summaryText 摘要文本（首行为标题，其余为正文）
     * @return 包含 issueId, fileName, title, filePath 的结果
     * @throws IOException 文件写入失败时抛出
     */
    Map<String, Object> writeIssueLog(String workingDir, String sessionId, String summaryText) throws IOException {
        Path issuesDir = Paths.get(workingDir, "docs", "issue-log");
        Files.createDirectories(issuesDir);

        // 1. 确定 issue 编号
        String issueId = nextIssueId(issuesDir);

        // 2. 拆分标题和正文
        String title;
        String body;
        int firstNewline = summaryText.indexOf('\n');
        if (firstNewline > 0) {
            title = summaryText.substring(0, firstNewline).trim();
            body = summaryText.substring(firstNewline).trim();
        } else {
            title = summaryText;
            body = summaryText;
        }

        // 3. 生成安全文件名
        String safeTitle = title.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fff_-]", "");
        if (safeTitle.length() > 40) {
            safeTitle = safeTitle.substring(0, 40);
        }
        if (safeTitle.isEmpty()) {
            safeTitle = "summary";
        }
        String fileName = issueId + "-" + safeTitle + ".md";

        // 4. 写入 issue 文件
        Path issueFile = issuesDir.resolve(fileName);
        String issueContent = "# " + issueId + " " + title + "\n\n"
                + "- **会话ID**: " + sessionId + "\n"
                + "- **工作目录**: " + workingDir + "\n"
                + "- **创建时间**: " + LocalDate.now() + "\n\n"
                + body + "\n";
        Files.write(issueFile, issueContent.getBytes(StandardCharsets.UTF_8));

        // 5. 更新 index.md
        updateIndex(issuesDir, issueId, fileName, title);

        Map<String, Object> result = new HashMap<>();
        result.put("issueId", issueId);
        result.put("fileName", fileName);
        result.put("title", title);
        result.put("filePath", issueFile.toString());
        return result;
    }

    private String nextIssueId(Path issuesDir) {
        int nextNum = 1;
        File[] existing = issuesDir.toFile().listFiles((dir, name) -> name.matches("I-\\d{3}-.*\\.md"));
        if (existing != null) {
            for (File f : existing) {
                try {
                    int num = Integer.parseInt(f.getName().substring(2, 5));
                    if (num >= nextNum) {
                        nextNum = num + 1;
                    }
                } catch (NumberFormatException ignored) {
                    // skip malformed filenames
                }
            }
        }
        return String.format("I-%03d", nextNum);
    }

    private void updateIndex(Path issuesDir, String issueId, String fileName, String title) throws IOException {
        Path indexFile = issuesDir.resolve("index.md");
        StringBuilder indexContent = new StringBuilder();
        if (Files.exists(indexFile)) {
            indexContent.append(new String(Files.readAllBytes(indexFile), StandardCharsets.UTF_8));
        } else {
            indexContent.append("# Issue Log\n\n")
                    .append("| ID | 标题 | 日期 |\n")
                    .append("|------|------|------|\n");
        }
        indexContent.append("| [").append(issueId).append("](").append(fileName).append(") | ")
                .append(title).append(" | ").append(LocalDate.now()).append(" |\n");
        Files.write(indexFile, indexContent.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void parseJsonLine(String line, StringBuilder plainText) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(line);
            // stream-json content_block_delta with text
            if (node.has("type") && "content_block_delta".equals(node.get("type").asText())) {
                JsonNode delta = node.get("delta");
                if (delta != null && delta.has("text")) {
                    plainText.append(delta.get("text").asText());
                }
            }
            // result type
            if (node.has("type") && "result".equals(node.get("type").asText())) {
                if (node.has("result")) {
                    plainText.setLength(0);
                    plainText.append(node.get("result").asText());
                }
            }
        } catch (Exception ignored) {
            // not valid JSON, skip
        }
    }
}
