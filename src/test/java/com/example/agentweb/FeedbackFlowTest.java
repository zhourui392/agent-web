package com.example.agentweb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话反馈评价端到端流程,顺带验证 chat_session 反馈列迁移可用。
 * @author zhourui(V33215020)
 * @since 2026-05-20
 */
@SpringBootTest(properties = {
        "agent.fs.roots=/tmp",
        "agent.cli.codex.stdin=false"
})
@AutoConfigureMockMvc
@Transactional
@Tag("spring-flow")
@ResourceLock("spring-flow-sqlite")
public class FeedbackFlowTest {

    @DynamicPropertySource
    static void configureEchoCli(DynamicPropertyRegistry registry) {
        TestCliStub.register(registry);
    }

    @Autowired
    private MockMvc mvc;

    private String createSession() throws Exception {
        Path tmp = Files.createTempDirectory("agent-web-feedback-test");
        String body = "{\"agentType\":\"CODEX\",\"workingDir\":\"" + tmp.toString().replace("\\", "\\\\") + "\"}";
        String resp = mvc.perform(post("/api/chat/session").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return resp.replaceAll(".*\"sessionId\":\"([^\"]+)\".*", "$1");
    }

    @Test
    public void getFeedback_new_session_returns_empty_feedback() throws Exception {
        String sessionId = createSession();

        mvc.perform(get("/api/chat/session/" + sessionId + "/feedback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").isEmpty())
                .andExpect(jsonPath("$.comment").isEmpty());
    }

    @Test
    public void putFeedback_rating_and_comment_can_be_read_back() throws Exception {
        String sessionId = createSession();

        mvc.perform(put("/api/chat/session/" + sessionId + "/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":\"CORRECT\",\"comment\":\"分析准确\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value("CORRECT"))
                .andExpect(jsonPath("$.comment").value("分析准确"))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        mvc.perform(get("/api/chat/session/" + sessionId + "/feedback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value("CORRECT"))
                .andExpect(jsonPath("$.comment").value("分析准确"));
    }

    @Test
    public void putFeedback_full_replace_clears_comment_on_resubmit() throws Exception {
        String sessionId = createSession();

        mvc.perform(put("/api/chat/session/" + sessionId + "/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":\"PARTIALLY_CORRECT\",\"comment\":\"先写点东西\"}"))
                .andExpect(status().isOk());

        // 整体替换:只传 rating,备注被覆盖为空
        mvc.perform(put("/api/chat/session/" + sessionId + "/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":\"INCORRECT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value("INCORRECT"))
                .andExpect(jsonPath("$.comment").isEmpty());

        mvc.perform(get("/api/chat/session/" + sessionId + "/feedback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value("INCORRECT"))
                .andExpect(jsonPath("$.comment").isEmpty());
    }

    @Test
    public void getFeedback_session_not_found_returns_400() throws Exception {
        mvc.perform(get("/api/chat/session/not-a-real-session/feedback"))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void putFeedback_invalid_rating_returns_400() throws Exception {
        String sessionId = createSession();

        mvc.perform(put("/api/chat/session/" + sessionId + "/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":\"BANANA\"}"))
                .andExpect(status().isBadRequest());
    }
}
