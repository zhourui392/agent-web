package com.example.agentweb.interfaces;

import com.example.agentweb.app.harness.HarnessAppService;
import com.example.agentweb.app.harness.HarnessRunQueryService;
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
 * Feature Flag 关闭时不注册 Harness HTTP 入口。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(HarnessController.class)
@TestPropertySource(properties = "agent.harness.enabled=false")
class HarnessFeatureFlagTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private HarnessAppService appService;

    @MockBean
    private HarnessRunQueryService queryService;

    @Test
    void disabled_should_not_expose_harness_endpoint() throws Exception {
        mvc.perform(get("/api/harness/runs/run-1"))
                .andExpect(status().isNotFound());
    }
}
