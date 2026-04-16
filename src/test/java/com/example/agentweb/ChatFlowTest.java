package com.example.agentweb;

import org.junit.jupiter.api.Test;
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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "agent.fs.roots=/tmp",
        "agent.cli.codex.stdin=false"
})
@AutoConfigureMockMvc
@Transactional
public class ChatFlowTest {

    /**
     * Windows 没有 {@code /bin/echo}，改用 {@code cmd /c echo} 作为跨平台 stub。
     * 期望产物为 {@code "Echo " + userMessage}。
     */
    @DynamicPropertySource
    static void configureEchoCli(DynamicPropertyRegistry registry) {
        TestCliStub.register(registry);
    }

    @Autowired
    private MockMvc mvc;

    @Test
    public void start_and_send_should_work() throws Exception {
        Path tmp = Files.createTempDirectory("agent-web-test");
        String body = "{\n  \"agentType\": \"CODEX\",\n  \"workingDir\": \"" + tmp.toString().replace("\\", "\\\\") + "\"\n}";
        String resp = mvc.perform(post("/api/chat/session").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String sessionId = resp.replaceAll(".*\"sessionId\":\"([^\"]+)\".*", "$1");

        mvc.perform(post("/api/chat/session/" + sessionId + "/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.output", containsString("Echo hello")));
    }
}
