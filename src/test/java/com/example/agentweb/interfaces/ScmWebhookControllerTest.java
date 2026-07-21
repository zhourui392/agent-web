package com.example.agentweb.interfaces;

import com.example.agentweb.app.delivery.ScmWebhookAppService;
import com.example.agentweb.app.requirement.RequirementProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * webhook 边界：secret 未配置 fail-closed、缺/错 token 401/403、CIDR 白名单、放行后委托 app。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@WebMvcTest(ScmWebhookController.class)
@TestPropertySource(properties = "agent.requirement.enabled=true")
public class ScmWebhookControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ScmWebhookAppService webhookAppService;
    @MockBean
    private RequirementProperties properties;

    /** SessionAuthFilter 身份决议统一走 AuthAppService, 切片测试放行所有请求。 */
    @MockBean
    private com.example.agentweb.app.auth.AuthAppService authAppService;
    /** ApiKeyAuthFilter 构造依赖, 切片上下文必须提供。 */
    @MockBean
    private com.example.agentweb.infra.auth.ApiKeyProperties apiKeyProperties;
    @MockBean
    private com.example.agentweb.infra.auth.AuthProperties authProperties;
    @MockBean
    private com.example.agentweb.infra.auth.ThreadLocalUserContext userContext;
    @MockBean
    private com.example.agentweb.domain.auth.ManualSessionRepository manualSessionRepository;

    private RequirementProperties.Delivery delivery;

    @BeforeEach
    public void stubProperties() {
        delivery = new RequirementProperties.Delivery();
        delivery.setWebhookSecret("s3cret");
        when(properties.getDelivery()).thenReturn(delivery);
    }

    @Test
    public void secret_not_configured_should_fail_closed() throws Exception {
        delivery.setWebhookSecret("");

        mvc.perform(webhook("s3cret")).andExpect(status().isServiceUnavailable());
        verify(webhookAppService, never()).handle(nullable(String.class), nullable(String.class),
                nullable(String.class));
    }

    @Test
    public void missing_token_should_be_401() throws Exception {
        mvc.perform(post("/api/scm/webhook").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void wrong_token_should_be_403() throws Exception {
        mvc.perform(webhook("wrong")).andExpect(status().isForbidden());
    }

    @Test
    public void valid_token_should_delegate_with_headers() throws Exception {
        mvc.perform(webhook("s3cret").header("X-Gitlab-Event-UUID", "uuid-9"))
                .andExpect(status().isOk());

        verify(webhookAppService).handle(eq("uuid-9"), eq("Merge Request Hook"), eq("{}"));
    }

    @Test
    public void cidr_mismatch_should_be_403() throws Exception {
        // MockMvc remoteAddr 默认 127.0.0.1
        delivery.setWebhookAllowedCidrs(List.of("10.0.0.0/8"));

        mvc.perform(webhook("s3cret")).andExpect(status().isForbidden());
    }

    @Test
    public void cidr_match_should_pass() throws Exception {
        delivery.setWebhookAllowedCidrs(List.of("10.0.0.0/8", "127.0.0.0/8"));

        mvc.perform(webhook("s3cret")).andExpect(status().isOk());
    }

    @Test
    public void exact_ip_entry_should_pass() throws Exception {
        delivery.setWebhookAllowedCidrs(List.of("127.0.0.1"));

        mvc.perform(webhook("s3cret")).andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder webhook(String token) {
        return post("/api/scm/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Gitlab-Token", token)
                .header("X-Gitlab-Event", "Merge Request Hook")
                .content("{}");
    }
}
