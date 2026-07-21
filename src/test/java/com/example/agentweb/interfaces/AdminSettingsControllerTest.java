package com.example.agentweb.interfaces;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.infra.auth.AuthProperties;
import com.example.agentweb.infra.auth.ApiKeyProperties;
import com.example.agentweb.infra.setting.RuntimeAgentSettings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC slice test for {@link AdminSettingsController}.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-25
 */
@WebMvcTest(AdminSettingsController.class)
@Import(GlobalExceptionHandler.class)
public class AdminSettingsControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private RuntimeAgentSettings runtimeAgentSettings;

    @MockBean
    private ApiKeyProperties apiKeyProperties;
    @MockBean
    private AuthProperties authProperties;

    /** SessionAuthFilter 身份决议统一走 AuthAppService, 切片测试放行所有请求。 */
    @MockBean
    private com.example.agentweb.app.auth.AuthAppService authAppService;

    @MockBean
    private com.example.agentweb.infra.auth.ThreadLocalUserContext userContext;
    @MockBean
    private com.example.agentweb.domain.auth.ManualSessionRepository manualSessionRepository;

    @Test
    public void get_should_return_current_models_and_options() throws Exception {
        when(runtimeAgentSettings.getChatDefaultAgent()).thenReturn(AgentType.CLAUDE);

        mvc.perform(get("/api/admin-settings/agent-models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatDefaultAgent").value("CLAUDE"))
                .andExpect(jsonPath("$.options.length()").value(2))
                .andExpect(jsonPath("$.options[0]").value("CODEX"))
                .andExpect(jsonPath("$.options[1]").value("CLAUDE"));
    }

    @Test
    public void put_valid_should_persist_and_return_updated() throws Exception {
        mvc.perform(put("/api/admin-settings/agent-models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatDefaultAgent\":\"CODEX\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatDefaultAgent").value("CODEX"));

        verify(runtimeAgentSettings).setChatDefaultAgent(AgentType.CODEX);
    }

    @Test
    public void put_invalidAgent_should_return_400() throws Exception {
        mvc.perform(put("/api/admin-settings/agent-models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatDefaultAgent\":\"gemini\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void put_nativeAgent_should_return_400() throws Exception {
        mvc.perform(put("/api/admin-settings/agent-models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatDefaultAgent\":\"NATIVE\"}"))
                .andExpect(status().isBadRequest());
    }
}
