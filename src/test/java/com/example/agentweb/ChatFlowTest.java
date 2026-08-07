package com.example.agentweb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "agent.fs.roots=/tmp",
        "agent.cli.codex.stdin=false"
})
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@Tag("spring-flow")
@ResourceLock("spring-flow-sqlite")
public class ChatFlowTest {

    @Autowired
    private MockMvc mvc;

    @Test
    public void start_then_synchronous_send_route_should_be_retired() throws Exception {
        Path tmp = Files.createTempDirectory("agent-web-test");
        String body = "{\n  \"agentType\": \"CODEX\",\n  \"workingDir\": \"" + tmp.toString().replace("\\", "\\\\") + "\"\n}";
        String resp = mvc.perform(post("/api/chat/session").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String sessionId = resp.replaceAll(".*\"sessionId\":\"([^\"]+)\".*", "$1");

        mvc.perform(post("/api/chat/session/" + sessionId + "/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isNotFound());
    }
}
