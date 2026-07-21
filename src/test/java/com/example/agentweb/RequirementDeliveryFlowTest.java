package com.example.agentweb;

import com.example.agentweb.app.delivery.DeliveryAppService;
import com.example.agentweb.app.requirement.RequirementAppService;
import com.example.agentweb.app.workspace.WorkspaceAppService;
import com.example.agentweb.domain.delivery.MergeRequestRef;
import com.example.agentweb.domain.requirement.RequirementSource;
import com.example.agentweb.infra.git.GitCredentialCipher;
import com.example.agentweb.infra.setting.AppSettingRepository;
import com.example.agentweb.support.GitRepoFixture;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全栈锚点（verification-plan 预算第 2 条，M2 交付主链）：需求 → 真实 git 工作区供给 →
 * push（trailer 回链 + 显式 refspec 推到裸仓 origin）→ 草稿 MR（内嵌 HttpServer 扮演 GitLab REST）
 * → webhook MrMerged（secret 鉴权 REST 入口）→ DELIVERED [T10]。
 * 凭证走「系统默认账号 app_setting 密文」链路（cipher 用测试密钥 @Primary 覆盖）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class RequirementDeliveryFlowTest {

    private static final String OWNER = "V33215020";
    private static final String HOOK_SECRET = "anchor-hook-secret";
    private static final String CIPHER_KEY_B64 =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    /** 不用 @TempDir：其静态注入与 @DynamicPropertySource 的上下文创建时序无保障（M1 结论）。 */
    private static final Path TEMP_ROOT = createTempRoot();

    /** 扮演 GitLab REST 的内嵌 HttpServer：静态块启动保证端口先于属性绑定可用。 */
    private static final HttpServer GITLAB_STUB = startGitlabStub();

    private static final CopyOnWriteArrayList<String> STUB_TOKENS = new CopyOnWriteArrayList<>();

    private static GitRepoFixture fixture;

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("delivery-anchor");
        } catch (IOException e) {
            throw new IllegalStateException("创建锚点测试临时目录失败", e);
        }
    }

    private static HttpServer startGitlabStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v4/", RequirementDeliveryFlowTest::answerMrCreate);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("GitLab stub 启动失败", e);
        }
    }

    private static void answerMrCreate(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        STUB_TOKENS.add(exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN"));
        byte[] body = ("{\"iid\":7,\"web_url\":\"http://gitlab-stub/mr/7\",\"draft\":true,"
                + "\"head_pipeline\":null}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(201, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        TestCliStub.register(registry);
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + TEMP_ROOT.resolve("anchor.db").toAbsolutePath());
        registry.add("agent.requirement.enabled", () -> "true");
        registry.add("agent.requirement.workspace.root",
                () -> TEMP_ROOT.resolve("ws-root").toString());
        registry.add("agent.requirement.workspace.repo-url", RequirementDeliveryFlowTest::remoteRepoUrl);
        registry.add("agent.requirement.delivery.gitlab-base-url",
                () -> "http://127.0.0.1:" + GITLAB_STUB.getAddress().getPort());
        registry.add("agent.requirement.delivery.default-account-username", () -> "agent-bot");
        registry.add("agent.requirement.delivery.webhook-secret", () -> HOOK_SECRET);
    }

    private static synchronized String remoteRepoUrl() {
        if (fixture == null) {
            fixture = GitRepoFixture.createBare(TEMP_ROOT.resolve("origin.git"))
                    .withCommit("README.md", "seed", "init");
        }
        return fixture.url();
    }

    @TestConfiguration
    static class CipherOverride {

        /** 生产 cipher 依赖 env GIT_CRED_ENC_KEY；锚点用固定测试密钥启用加密链路。 */
        @Bean
        @Primary
        public GitCredentialCipher testGitCredentialCipher() {
            return new GitCredentialCipher(CIPHER_KEY_B64);
        }
    }

    @Autowired
    private RequirementAppService requirementAppService;
    @Autowired
    private WorkspaceAppService workspaceAppService;
    @Autowired
    private DeliveryAppService deliveryAppService;
    @Autowired
    private AppSettingRepository appSettings;
    @Autowired
    private GitCredentialCipher cipher;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TestRestTemplate rest;

    @Test
    public void deliver_chain_should_push_create_draft_mr_and_deliver_on_merge_webhook() {
        appSettings.put("delivery.gitlab.default-token", cipher.encrypt("stub-token"),
                System.currentTimeMillis());

        String requirementId = requirementAppService.create("交付锚点", null, OWNER, RequirementSource.BOARD);
        requirementAppService.attachPlan(requirementId, "1. 交付", OWNER);
        requirementAppService.approve(requirementId, OWNER);
        workspaceAppService.provisionFor(requirementId);
        requirementAppService.startImplement(requirementId, OWNER);

        MergeRequestRef mr = deliveryAppService.deliverDraft(requirementId, OWNER);

        assertEquals(7, mr.getMrIid());
        assertTrue(mr.isDraft());
        assertEquals("stub-token", STUB_TOKENS.get(STUB_TOKENS.size() - 1),
                "MR 创建应携带默认账号 token");
        // 裸仓 origin 上 req/* 分支已存在,且 HEAD 带交付回链 trailer(默认账号必有 Operated-By)
        Path origin = Paths.get(TEMP_ROOT.resolve("origin.git").toString());
        String branchTip = GitRepoFixture.git(origin, "rev-parse", "refs/heads/req/" + requirementId);
        assertTrue(branchTip != null && !branchTip.isBlank());
        String message = GitRepoFixture.git(origin, "log", "-1", "--format=%B",
                "refs/heads/req/" + requirementId);
        assertTrue(message.contains("Agent-Web-Session:"), "交付提交应带回链 trailer");
        assertTrue(message.contains("Operated-By: " + OWNER), "默认账号交付应记录实际操作人");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM merge_request_ref WHERE requirement_id=? AND mr_iid=7",
                Integer.class, requirementId));

        // 按状态机走到 REVIEW(T7 验证 → T8 VERIFIED),webhook 才允许 T10 落 DELIVERED
        requirementAppService.startVerify(requirementId, OWNER);
        requirementAppService.applyVerificationOutcome(requirementId,
                com.example.agentweb.domain.verification.VerificationOutcome.VERIFIED, "system:verify");

        // webhook MrMerged → DELIVERED [T10],actor 记 system:webhook
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Gitlab-Token", HOOK_SECRET);
        headers.set("X-Gitlab-Event", "Merge Request Hook");
        headers.set("X-Gitlab-Event-UUID", "anchor-uuid-1");
        String payload = "{\"object_attributes\":{\"iid\":7,\"action\":\"merge\",\"state\":\"merged\","
                + "\"source_branch\":\"req/" + requirementId + "\"},\"user\":{\"username\":\"reviewer\"}}";
        ResponseEntity<String> response = rest.postForEntity("/api/scm/webhook",
                new HttpEntity<>(payload, headers), String.class);

        assertEquals(200, response.getStatusCode().value(), "webhook 应放行: " + response.getBody());
        assertEquals("DELIVERED", jdbc.queryForObject(
                "SELECT status FROM requirement WHERE id=?", String.class, requirementId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM requirement_event WHERE requirement_id=? AND event_type='DELIVERED'"
                        + " AND actor='system:webhook'", Integer.class, requirementId));
    }
}
