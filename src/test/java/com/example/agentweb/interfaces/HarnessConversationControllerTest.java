package com.example.agentweb.interfaces;

import com.example.agentweb.app.harness.HarnessConversationMessageView;
import com.example.agentweb.app.harness.HarnessConversationQueryService;
import com.example.agentweb.app.harness.HarnessConversationService;
import com.example.agentweb.app.harness.HarnessConversationTurnResult;
import com.example.agentweb.app.harness.HarnessExecutionResult;
import com.example.agentweb.domain.harness.HarnessStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Harness 阶段对话 API 的参数与 HTTP 契约测试。
 *
 * @author alex
 * @since 2026-07-24
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(HarnessConversationController.class)
@TestPropertySource(properties = "agent.harness.enabled=true")
@Import(GlobalExceptionHandler.class)
class HarnessConversationControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private HarnessConversationService conversationService;

    @MockBean
    private HarnessConversationQueryService queryService;

    @Test
    void send_should_accept_message_and_return_runtime_identity() throws Exception {
        when(conversationService.send(any())).thenReturn(new HarnessConversationTurnResult(
                new HarnessExecutionResult("exec-2", "run-1", HarnessStage.DESIGN,
                        "STARTING", false, 2), false));

        mvc.perform(post("/api/harness/runs/run-1/stages/DESIGN/conversation")
                        .header("Idempotency-Key", "message-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"请调整缓存一致性方案\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value("run-1"))
                .andExpect(jsonPath("$.stage").value("DESIGN"))
                .andExpect(jsonPath("$.attemptNumber").value(2))
                .andExpect(jsonPath("$.executionId").value("exec-2"));

        verify(conversationService).send(any());
    }

    @Test
    void send_should_reject_blank_message_and_missing_idempotency_key() throws Exception {
        mvc.perform(post("/api/harness/runs/run-1/stages/ANALYSIS/conversation")
                        .header("Idempotency-Key", "message-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/harness/runs/run-1/stages/ANALYSIS/conversation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"有效消息\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_HARNESS_IDEMPOTENCY_KEY"));
    }

    @Test
    void list_should_return_ordered_conversation_projection() throws Exception {
        when(queryService.list("run-1")).thenReturn(Collections.singletonList(
                new HarnessConversationMessageView("event-1", "USER", "ANALYSIS",
                        1, "梳理需求", "text/plain", null, 123L)));

        mvc.perform(get("/api/harness/runs/run-1/conversation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("USER"))
                .andExpect(jsonPath("$[0].content").value("梳理需求"));
    }
}
