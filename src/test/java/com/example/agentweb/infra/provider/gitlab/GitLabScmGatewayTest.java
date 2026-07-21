package com.example.agentweb.infra.provider.gitlab;

import com.example.agentweb.adapter.delivery.CreateMrCommand;
import com.example.agentweb.adapter.delivery.PushBranchCommand;
import com.example.agentweb.adapter.delivery.ScmCredential;
import com.example.agentweb.adapter.delivery.ScmCredentialStore;
import com.example.agentweb.adapter.delivery.ScmWebhookEvent;
import com.example.agentweb.adapter.delivery.WebhookEnvelope;
import com.example.agentweb.domain.delivery.MergeRequestRef;
import com.example.agentweb.support.GitRepoFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * GitLab SCM 网关测试:MR REST 协议(MockRestServiceServer)、push 真实 git 裸仓(GitRepoFixture)、
 * webhook 防腐解析五类事件。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class GitLabScmGatewayTest {

    private static final String BASE_URL = "http://gitlab.test";
    private static final String REPO_URL = "http://gitlab.test/group/sub/app.git";

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private ScmCredentialStore credentialStore;
    private GitLabScmGateway gateway;

    @BeforeEach
    public void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        credentialStore = Mockito.mock(ScmCredentialStore.class);
        gateway = new GitLabScmGateway(restTemplate, BASE_URL, credentialStore);
    }

    // ---- createDraftMergeRequest / fetchMergeRequest ----

    @Test
    public void createDraftMergeRequest_should_post_with_encoded_project_path_and_token() {
        // given: project path 中的 / 需编码为 %2F
        server.expect(requestTo(BASE_URL + "/api/v4/projects/group%2Fsub%2Fapp/merge_requests"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("PRIVATE-TOKEN", "tok-mr"))
                .andExpect(jsonPath("$.source_branch").value("req/R2607040001"))
                .andExpect(jsonPath("$.target_branch").value("main"))
                .andExpect(jsonPath("$.title").value("Draft: feat x"))
                .andExpect(jsonPath("$.description").value("需求回链 R2607040001"))
                .andRespond(withSuccess(
                        "{\"iid\":42,\"web_url\":\"http://gitlab.test/group/sub/app/-/merge_requests/42\","
                                + "\"draft\":true}",
                        MediaType.APPLICATION_JSON));

        // when
        MergeRequestRef ref = gateway.createDraftMergeRequest(new CreateMrCommand(
                REPO_URL, "req/R2607040001", "main", "Draft: feat x", "需求回链 R2607040001",
                new ScmCredential("alice", "tok-mr", false)));

        // then
        assertEquals(42, ref.getMrIid());
        assertEquals("http://gitlab.test/group/sub/app/-/merge_requests/42", ref.getUrl());
        assertTrue(ref.isDraft());
        assertNull(ref.getPipelineStatus());
        server.verify();
    }

    @Test
    public void fetchMergeRequest_should_use_default_account_and_parse_head_pipeline_status() {
        // given
        when(credentialStore.findDefaultAccount())
                .thenReturn(Optional.of(new ScmCredential("agent-bot", "tok-default", true)));
        server.expect(requestTo(BASE_URL + "/api/v4/projects/group%2Fsub%2Fapp/merge_requests/42"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("PRIVATE-TOKEN", "tok-default"))
                .andRespond(withSuccess(
                        "{\"iid\":42,\"web_url\":\"http://gitlab.test/group/sub/app/-/merge_requests/42\","
                                + "\"draft\":false,\"head_pipeline\":{\"status\":\"failed\"}}",
                        MediaType.APPLICATION_JSON));

        // when
        MergeRequestRef ref = gateway.fetchMergeRequest(REPO_URL, 42);

        // then
        assertEquals(42, ref.getMrIid());
        assertEquals("failed", ref.getPipelineStatus());
        server.verify();
    }

    @Test
    public void fetchMergeRequest_without_default_account_should_throw() {
        when(credentialStore.findDefaultAccount()).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> gateway.fetchMergeRequest(REPO_URL, 42));
    }

    // ---- pushBranch ----

    @Nested
    class PushBranch {

        @TempDir
        Path tempDir;

        private GitRepoFixture originFixture;
        private Path clone;

        @BeforeEach
        public void setUpRepo() throws Exception {
            originFixture = GitRepoFixture.createBare(tempDir.resolve("origin.git"))
                    .withCommit("README.md", "hello", "init");
            clone = tempDir.resolve("clone");
            GitRepoFixture.git(tempDir, "clone", originFixture.url(), clone.toString());
            GitRepoFixture.git(clone, "config", "user.name", "tester");
            GitRepoFixture.git(clone, "config", "user.email", "tester@test.local");
            GitRepoFixture.git(clone, "config", "commit.gpgsign", "false");
            GitRepoFixture.git(clone, "checkout", "-b", "req/RTEST0001");
            Files.writeString(clone.resolve("work.txt"), "work", StandardCharsets.UTF_8);
            GitRepoFixture.git(clone, "add", "-A");
            GitRepoFixture.git(clone, "commit", "-m", "feat: work");
        }

        @Test
        public void pushBranch_should_append_trailer_commit_and_push_explicit_refspec() {
            // given
            PushBranchCommand cmd = new PushBranchCommand(clone.toString(), originFixture.url(),
                    "req/RTEST0001",
                    List.of("Agent-Web-Session: S2607040001", "Agent-Web-Requirement: RTEST0001"), null);

            // when
            gateway.pushBranch(cmd);

            // then: origin 出现该分支
            GitRepoFixture.GitOutcome refCheck = GitRepoFixture.tryGit(
                    Paths.get(originFixture.url()), "rev-parse", "refs/heads/req/RTEST0001");
            assertEquals(0, refCheck.exitCode());

            // then: HEAD message 含交付回链 trailer
            String headMessage = GitRepoFixture.git(clone, "log", "-1", "--format=%B");
            assertTrue(headMessage.contains("Agent-Web-Session: S2607040001"));
            assertTrue(headMessage.contains("Agent-Web-Requirement: RTEST0001"));

            // when: 二次 push(HEAD 已含标记)
            String countBefore = GitRepoFixture.git(clone, "rev-list", "--count", "req/RTEST0001").trim();
            gateway.pushBranch(cmd);

            // then: 不再追加标记提交,幂等
            String countAfter = GitRepoFixture.git(clone, "rev-list", "--count", "req/RTEST0001").trim();
            assertEquals(countBefore, countAfter);
        }

        @Test
        public void pushBranch_without_trailers_should_not_append_marker_commit() {
            // given
            String countBefore = GitRepoFixture.git(clone, "rev-list", "--count", "HEAD").trim();
            PushBranchCommand cmd = new PushBranchCommand(clone.toString(), originFixture.url(),
                    "req/RTEST0001", List.of(), null);

            // when
            gateway.pushBranch(cmd);

            // then: 本地提交数不变,远端分支已建
            assertEquals(countBefore, GitRepoFixture.git(clone, "rev-list", "--count", "HEAD").trim());
            assertEquals(0, GitRepoFixture.tryGit(
                    Paths.get(originFixture.url()), "rev-parse", "refs/heads/req/RTEST0001").exitCode());
        }

        @Test
        public void pushBranch_failure_should_throw_with_exit_code_and_masked_output() {
            // given: 指向不存在的远端,push 必失败
            GitRepoFixture.git(clone, "remote", "set-url", "origin",
                    tempDir.resolve("no-such-repo.git").toString());
            PushBranchCommand cmd = new PushBranchCommand(clone.toString(), originFixture.url(),
                    "req/RTEST0001", List.of(), null);

            // when / then
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> gateway.pushBranch(cmd));
            assertTrue(ex.getMessage().contains("exit="));
        }
    }

    // ---- parseWebhook ----

    @Test
    public void parseWebhook_pipeline_failed_should_map_to_pipeline_failed_event() {
        String body = """
                {"object_kind":"pipeline",
                 "object_attributes":{"id":31,"ref":"req/R2607040001","status":"failed",
                   "url":"http://gitlab.test/group/app/-/pipelines/31"},
                 "project":{"web_url":"http://gitlab.test/group/app"}}
                """;

        ScmWebhookEvent event = gateway.parseWebhook(new WebhookEnvelope("Pipeline Hook", body));

        ScmWebhookEvent.PipelineFailed failed = assertInstanceOf(ScmWebhookEvent.PipelineFailed.class, event);
        assertEquals("req/R2607040001", failed.ref());
        assertEquals("http://gitlab.test/group/app/-/pipelines/31", failed.pipelineUrl());
        assertEquals("failed", failed.status());
    }

    @Test
    public void parseWebhook_pipeline_failed_without_url_should_compose_from_project_web_url() {
        String body = """
                {"object_attributes":{"id":31,"ref":"req/R2607040001","status":"failed"},
                 "project":{"web_url":"http://gitlab.test/group/app"}}
                """;

        ScmWebhookEvent event = gateway.parseWebhook(new WebhookEnvelope("Pipeline Hook", body));

        ScmWebhookEvent.PipelineFailed failed = assertInstanceOf(ScmWebhookEvent.PipelineFailed.class, event);
        assertEquals("http://gitlab.test/group/app/-/pipelines/31", failed.pipelineUrl());
    }

    @Test
    public void parseWebhook_pipeline_success_should_be_unsupported() {
        String body = "{\"object_attributes\":{\"id\":31,\"ref\":\"main\",\"status\":\"success\"}}";

        ScmWebhookEvent event = gateway.parseWebhook(new WebhookEnvelope("Pipeline Hook", body));

        assertInstanceOf(ScmWebhookEvent.Unsupported.class, event);
    }

    @Test
    public void parseWebhook_mr_note_should_map_and_truncate_excerpt_to_200_chars() {
        String longNote = "x".repeat(250);
        String body = """
                {"object_attributes":{"note":"%s",
                   "url":"http://gitlab.test/group/app/-/merge_requests/5#note_1"},
                 "user":{"username":"bob"},
                 "merge_request":{"iid":5,"source_branch":"req/R2607040001"}}
                """.formatted(longNote);

        ScmWebhookEvent event = gateway.parseWebhook(new WebhookEnvelope("Note Hook", body));

        ScmWebhookEvent.MrNoteAdded note = assertInstanceOf(ScmWebhookEvent.MrNoteAdded.class, event);
        assertEquals(5, note.mrIid());
        assertEquals("req/R2607040001", note.sourceBranch());
        assertEquals("bob", note.authorUsername());
        assertEquals(200, note.noteExcerpt().length());
        assertEquals("http://gitlab.test/group/app/-/merge_requests/5#note_1", note.noteUrl());
    }

    @Test
    public void parseWebhook_non_mr_note_should_be_unsupported() {
        String body = "{\"object_attributes\":{\"note\":\"on commit\"},\"user\":{\"username\":\"bob\"}}";

        ScmWebhookEvent event = gateway.parseWebhook(new WebhookEnvelope("Note Hook", body));

        assertInstanceOf(ScmWebhookEvent.Unsupported.class, event);
    }

    @Test
    public void parseWebhook_mr_merged_should_map_to_mr_merged_event() {
        String body = """
                {"object_attributes":{"iid":5,"source_branch":"req/R2607040001",
                   "action":"merge","state":"merged"},
                 "user":{"username":"carol"}}
                """;

        ScmWebhookEvent event = gateway.parseWebhook(new WebhookEnvelope("Merge Request Hook", body));

        ScmWebhookEvent.MrMerged merged = assertInstanceOf(ScmWebhookEvent.MrMerged.class, event);
        assertEquals(5, merged.mrIid());
        assertEquals("req/R2607040001", merged.sourceBranch());
        assertEquals("carol", merged.mergedByUsername());
    }

    @Test
    public void parseWebhook_mr_open_action_should_be_unsupported() {
        String body = """
                {"object_attributes":{"iid":5,"source_branch":"req/R1","action":"open","state":"opened"},
                 "user":{"username":"carol"}}
                """;

        ScmWebhookEvent event = gateway.parseWebhook(new WebhookEnvelope("Merge Request Hook", body));

        assertInstanceOf(ScmWebhookEvent.Unsupported.class, event);
    }

    @Test
    public void parseWebhook_issue_with_agent_dev_label_should_map_to_issue_labeled() {
        String body = """
                {"object_attributes":{"action":"open","url":"http://gitlab.test/group/app/-/issues/9",
                   "title":"支付回调偶发超时","description":"复现步骤..."},
                 "user":{"username":"dave"},
                 "labels":[{"title":"agent-dev"},{"title":"backend"}]}
                """;

        ScmWebhookEvent event = gateway.parseWebhook(new WebhookEnvelope("Issue Hook", body));

        ScmWebhookEvent.IssueLabeled labeled = assertInstanceOf(ScmWebhookEvent.IssueLabeled.class, event);
        assertEquals("http://gitlab.test/group/app/-/issues/9", labeled.issueUrl());
        assertEquals("支付回调偶发超时", labeled.title());
        assertEquals("复现步骤...", labeled.description());
        assertEquals("dave", labeled.authorUsername());
        assertEquals(List.of("agent-dev", "backend"), labeled.labels());
    }

    @Test
    public void parseWebhook_issue_without_agent_dev_label_should_be_unsupported() {
        String body = """
                {"object_attributes":{"action":"open","url":"http://gitlab.test/i/9","title":"t"},
                 "user":{"username":"dave"},
                 "labels":[{"title":"backend"}]}
                """;

        ScmWebhookEvent event = gateway.parseWebhook(new WebhookEnvelope("Issue Hook", body));

        assertInstanceOf(ScmWebhookEvent.Unsupported.class, event);
    }

    @Test
    public void parseWebhook_issue_should_match_configured_intake_label_only() {
        GitLabScmGateway customGateway =
                new GitLabScmGateway(restTemplate, BASE_URL, credentialStore, "ai-intake");
        String customLabelBody = """
                {"object_attributes":{"action":"open","url":"http://gitlab.test/i/10","title":"t"},
                 "user":{"username":"dave"},
                 "labels":[{"title":"ai-intake"}]}
                """;
        String defaultLabelBody = """
                {"object_attributes":{"action":"open","url":"http://gitlab.test/i/11","title":"t"},
                 "user":{"username":"dave"},
                 "labels":[{"title":"agent-dev"}]}
                """;

        assertInstanceOf(ScmWebhookEvent.IssueLabeled.class,
                customGateway.parseWebhook(new WebhookEnvelope("Issue Hook", customLabelBody)));
        assertInstanceOf(ScmWebhookEvent.Unsupported.class,
                customGateway.parseWebhook(new WebhookEnvelope("Issue Hook", defaultLabelBody)));
    }

    @Test
    public void parseWebhook_blank_configured_label_should_fall_back_to_default() {
        GitLabScmGateway blankLabelGateway =
                new GitLabScmGateway(restTemplate, BASE_URL, credentialStore, "  ");
        String defaultLabelBody = """
                {"object_attributes":{"action":"open","url":"http://gitlab.test/i/12","title":"t"},
                 "user":{"username":"dave"},
                 "labels":[{"title":"agent-dev"}]}
                """;

        assertInstanceOf(ScmWebhookEvent.IssueLabeled.class,
                blankLabelGateway.parseWebhook(new WebhookEnvelope("Issue Hook", defaultLabelBody)));
    }

    @Test
    public void parseWebhook_unknown_event_type_should_be_unsupported() {
        ScmWebhookEvent event = gateway.parseWebhook(new WebhookEnvelope("Deployment Hook", "{}"));

        ScmWebhookEvent.Unsupported unsupported = assertInstanceOf(ScmWebhookEvent.Unsupported.class, event);
        assertEquals("Deployment Hook", unsupported.eventType());
    }

    @Test
    public void parseWebhook_malformed_json_should_be_unsupported_not_throw() {
        ScmWebhookEvent event = gateway.parseWebhook(new WebhookEnvelope("Pipeline Hook", "{oops"));

        assertInstanceOf(ScmWebhookEvent.Unsupported.class, event);
    }
}
