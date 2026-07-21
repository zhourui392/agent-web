package com.example.agentweb.infra.provider.gitlab;

import com.example.agentweb.adapter.delivery.CreateMrCommand;
import com.example.agentweb.adapter.delivery.PushBranchCommand;
import com.example.agentweb.adapter.delivery.ScmCredential;
import com.example.agentweb.adapter.delivery.ScmCredentialStore;
import com.example.agentweb.adapter.delivery.ScmGateway;
import com.example.agentweb.adapter.delivery.ScmWebhookEvent;
import com.example.agentweb.adapter.delivery.WebhookEnvelope;
import com.example.agentweb.domain.delivery.MergeRequestRef;
import com.example.agentweb.infra.git.GitAskpassScript;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * GitLab SCM 网关:push 走 git CLI(凭证经 GIT_ASKPASS env 注入,不进 URL 不落盘),
 * MR 走 REST API v4(PRIVATE-TOKEN 头),webhook 解析委托 {@link GitLabWebhookParser}。
 *
 * <p>push 一律显式 refspec(禁裸 git push,与禁 --mirror 红线互为双保险);
 * 日志与异常消息统一打码 URL 内嵌凭证。装配由 @Configuration 负责,不带 Spring 注解。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Slf4j
public class GitLabScmGateway implements ScmGateway {

    private static final int GIT_TIMEOUT_SECONDS = 120;
    private static final int OUTPUT_SUMMARY_MAX = 800;

    /** 交付回链 trailer 键,HEAD 已含则不再追加标记提交(幂等) */
    private static final String SESSION_TRAILER_KEY = "Agent-Web-Session:";

    private static final String MARKER_COMMIT_SUBJECT = "chore(delivery): agent-web 交付回链";

    private final RestTemplate restTemplate;
    private final String gitlabBaseUrl;
    private final ScmCredentialStore credentialStore;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GitAskpassScript askpassScript = new GitAskpassScript();
    private final GitLabWebhookParser webhookParser;

    /** 沿用默认 issue 接入标签 agent-dev 的便捷构造器（测试与旧调用方）。 */
    public GitLabScmGateway(RestTemplate restTemplate, String gitlabBaseUrl,
                            ScmCredentialStore credentialStore) {
        this(restTemplate, gitlabBaseUrl, credentialStore, GitLabWebhookParser.DEFAULT_INTAKE_LABEL);
    }

    /**
     * @param restTemplate     HTTP 客户端
     * @param gitlabBaseUrl    GitLab 根地址(如 https://gitlab.example.com)
     * @param credentialStore  凭证读取端口(fetchMergeRequest 用默认账号)
     * @param issueIntakeLabel issue 接入 agent 开发流程的标签名(空白回落默认)
     */
    public GitLabScmGateway(RestTemplate restTemplate, String gitlabBaseUrl,
                            ScmCredentialStore credentialStore, String issueIntakeLabel) {
        this.restTemplate = restTemplate;
        this.gitlabBaseUrl = stripTrailingSlash(gitlabBaseUrl);
        this.credentialStore = credentialStore;
        this.webhookParser = new GitLabWebhookParser(objectMapper, issueIntakeLabel);
    }

    // ---- pushBranch ----

    /**
     * worktree → origin 单分支推送;trailers 非空且 HEAD 未含回链时先追加交付标记空提交。
     *
     * @param cmd push 命令
     */
    @Override
    public void pushBranch(PushBranchCommand cmd) {
        appendDeliveryMarkerIfNeeded(cmd);
        runGit(cmd, "push", "origin", cmd.getBranch() + ":" + cmd.getBranch());
    }

    private void appendDeliveryMarkerIfNeeded(PushBranchCommand cmd) {
        List<String> trailers = cmd.getCommitTrailers();
        if (trailers == null || trailers.isEmpty()) {
            return;
        }
        String headMessage = runGit(cmd, "log", "-1", "--format=%B");
        if (headMessage.contains(SESSION_TRAILER_KEY)) {
            return;
        }
        commitDeliveryMarker(cmd, trailers);
    }

    /** 提交消息经 -F 临时文件传递:trailer 块含换行,Windows 下 -m 传参对换行不可靠 */
    private void commitDeliveryMarker(PushBranchCommand cmd, List<String> trailers) {
        Path messageFile = null;
        try {
            messageFile = Files.createTempFile("agent-web-delivery-msg-", ".txt");
            String message = MARKER_COMMIT_SUBJECT + "\n\n" + String.join("\n", trailers) + "\n";
            Files.writeString(messageFile, message, StandardCharsets.UTF_8);
            runGit(cmd, "commit", "--allow-empty", "-F", messageFile.toString());
        } catch (IOException e) {
            throw new IllegalStateException("交付标记提交消息文件写入失败", e);
        } finally {
            deleteQuietly(messageFile);
        }
    }

    private void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("gitlab-push-tempfile-cleanup-failed path={} reason={}", file, e.getMessage());
        }
    }

    private String runGit(PushBranchCommand cmd, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(args));
        GitResult result = execute(cmd, command);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("git 命令失败 exit=" + result.exitCode()
                    + " cmd=git " + sanitize(String.join(" ", args))
                    + " output=" + sanitize(summarize(result.output())));
        }
        return result.output();
    }

    private GitResult execute(PushBranchCommand cmd, List<String> command) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(cmd.getWorktreePath()));
        pb.redirectErrorStream(true);
        injectCredentialEnv(pb.environment(), cmd.getCredential());
        try {
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            Thread drainer = startDrainer(process, output);
            boolean finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
                drainer.join(1000L);
                throw new IllegalStateException("git 命令超时(" + GIT_TIMEOUT_SECONDS + "s) cmd="
                        + sanitize(String.join(" ", command)));
            }
            drainer.join(5000L);
            return new GitResult(process.exitValue(), output.toString());
        } catch (IOException e) {
            throw new IllegalStateException("git 进程启动失败 cmd=" + sanitize(String.join(" ", command)), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("git 进程被中断 cmd=" + sanitize(String.join(" ", command)), e);
        }
    }

    /** 凭证走一次性 askpass 脚本 + 子进程 env,绝不拼进 URL;禁终端交互防挂死 */
    private void injectCredentialEnv(Map<String, String> env, ScmCredential credential) {
        env.put("GIT_TERMINAL_PROMPT", "0");
        if (credential == null) {
            return;
        }
        try {
            env.put("GIT_ASKPASS", askpassScript.ensureScript());
        } catch (IOException e) {
            throw new IllegalStateException("git askpass 脚本准备失败", e);
        }
        env.put("AGENT_GIT_USERNAME", credential.getUsername());
        env.put("AGENT_GIT_PASSWORD", credential.getToken());
    }

    /** 独立线程排空子进程输出,避免主线程阻塞读导致 waitFor 超时失效 */
    private Thread startDrainer(Process process, StringBuilder output) {
        Thread drainer = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (IOException e) {
                log.warn("gitlab-push-output-drain-failed reason={}", e.getMessage());
            }
        }, "gitlab-push-drainer");
        drainer.setDaemon(true);
        drainer.start();
        return drainer;
    }

    // ---- MR REST ----

    /**
     * 创建草稿 MR。
     *
     * @param cmd 创建命令
     * @return MR 引用(pipelineStatus 为 null,尚未触发)
     */
    @Override
    public MergeRequestRef createDraftMergeRequest(CreateMrCommand cmd) {
        URI uri = URI.create(gitlabBaseUrl + "/api/v4/projects/" + encodeProjectPath(cmd.getRepoUrl())
                + "/merge_requests");
        ObjectNode body = objectMapper.createObjectNode();
        body.put("source_branch", cmd.getSourceBranch());
        body.put("target_branch", cmd.getTargetBranch());
        body.put("title", cmd.getTitle());
        body.put("description", cmd.getDescription());

        ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.POST,
                new HttpEntity<>(body.toString(), jsonHeaders(cmd.getCredential())), String.class);
        JsonNode node = readMrResponse(response.getBody());
        return new MergeRequestRef(node.path("iid").asLong(), textOrNull(node, "web_url"),
                node.path("draft").asBoolean(false), null);
    }

    /**
     * 查询 MR 当前状态(用系统默认账号凭证)。
     *
     * @param repoUrl 仓库地址
     * @param mrIid   MR 编号
     * @return MR 引用(含 head_pipeline.status,可空)
     */
    @Override
    public MergeRequestRef fetchMergeRequest(String repoUrl, long mrIid) {
        ScmCredential credential = credentialStore.findDefaultAccount()
                .orElseThrow(() -> new IllegalStateException("系统默认 GitLab 账号未配置,无法查询 MR"));
        URI uri = URI.create(gitlabBaseUrl + "/api/v4/projects/" + encodeProjectPath(repoUrl)
                + "/merge_requests/" + mrIid);

        ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(credential)), String.class);
        JsonNode node = readMrResponse(response.getBody());
        return new MergeRequestRef(node.path("iid").asLong(), textOrNull(node, "web_url"),
                node.path("draft").asBoolean(false), headPipelineStatus(node));
    }

    private HttpHeaders jsonHeaders(ScmCredential credential) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("PRIVATE-TOKEN", credential.getToken());
        return headers;
    }

    private JsonNode readMrResponse(String body) {
        try {
            return objectMapper.readTree(body == null ? "" : body);
        } catch (IOException e) {
            throw new IllegalStateException("GitLab MR 响应解析失败", e);
        }
    }

    /** 取字符串字段;缺失或 JSON null 返回 null(asText 对 NullNode 会返回 "null" 字符串,须显式判) */
    private String textOrNull(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private String headPipelineStatus(JsonNode node) {
        JsonNode headPipeline = node.path("head_pipeline");
        if (headPipeline.isMissingNode() || headPipeline.isNull()) {
            return null;
        }
        return textOrNull(headPipeline, "status");
    }

    /**
     * repoUrl → URL-encode 后的 GitLab project 路径:去 scheme+host 前缀、尾部 .git,
     * 再整体编码({@code /} → {@code %2F})。
     */
    private String encodeProjectPath(String repoUrl) {
        String path = repoUrl == null ? "" : repoUrl.trim();
        path = path.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]+/", "");
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        path = stripTrailingSlash(path);
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - ".git".length());
        }
        if (path.isBlank()) {
            throw new IllegalArgumentException("无法从 repoUrl 解析 GitLab project 路径: " + sanitize(repoUrl));
        }
        return URLEncoder.encode(path, StandardCharsets.UTF_8);
    }

    // ---- webhook ----

    /**
     * 防腐解析 webhook。
     *
     * @param envelope 原始信封
     * @return 平台事件;不认识/解析失败返回 Unsupported
     */
    @Override
    public ScmWebhookEvent parseWebhook(WebhookEnvelope envelope) {
        return webhookParser.parse(envelope);
    }

    // ---- 工具 ----

    private static String stripTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /** URL 内嵌凭证统一打码,日志与异常消息里不得出现明文 */
    private String sanitize(String text) {
        return text == null ? "" : text.replaceAll("://[^/@\\s]+@", "://***@");
    }

    private String summarize(String output) {
        String trimmed = output == null ? "" : output.trim();
        return trimmed.length() <= OUTPUT_SUMMARY_MAX
                ? trimmed
                : trimmed.substring(0, OUTPUT_SUMMARY_MAX) + "...";
    }

    /** git 退出码 + 合并输出(stderr 已并入) */
    private record GitResult(int exitCode, String output) {
    }
}
