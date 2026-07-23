package com.example.agentweb.interfaces;

import com.example.agentweb.app.harness.HarnessExecutionService;
import com.example.agentweb.app.harness.RuntimeExecutionQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Feature Flag 关闭时不注册 RuntimeExecution HTTP 入口。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(HarnessExecutionController.class)
@TestPropertySource(properties = "agent.harness.enabled=false")
class HarnessExecutionFeatureFlagTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private HarnessExecutionService executionService;

    @MockBean
    private RuntimeExecutionQueryService queryService;

    @Test
    void disabledShouldNotExposeRuntimeExecutionEndpoint() throws Exception {
        mvc.perform(get("/api/harness/runs/run-1/stages/ANALYSIS/attempts/1/execution"))
                .andExpect(status().isNotFound());
    }
}
