package com.example.agentweb.interfaces;

import com.example.agentweb.app.git.GitConfigAppService;
import com.example.agentweb.app.git.GitConfigView;
import com.example.agentweb.infra.auth.AuthProperties;
import com.example.agentweb.infra.auth.ThreadLocalUserContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC slice test for {@link GitConfigController}：状态码、凭证不回显、默认用户 409、身份非法 400。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
@WebMvcTest(GitConfigController.class)
@Import(GlobalExceptionHandler.class)
class GitConfigControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private GitConfigAppService service;

    // ── @WebMvcTest 装配 Filter Bean 时需补齐的构造依赖 ──
    @MockBean
    private AuthProperties authProperties;

    /** SessionAuthFilter 身份决议统一走 AuthAppService, 切片测试放行所有请求。 */
    @MockBean
    private com.example.agentweb.app.auth.AuthAppService authAppService;

    @MockBean
    private ThreadLocalUserContext userContext;

    /** 手动登录链路依赖, SessionAuthFilter 现在还要 manual provider + props + repo, 切片测试一并 mock。 */
    @MockBean
    private com.example.agentweb.domain.auth.ManualSessionRepository manualSessionRepository;

    @Test
    void get_should_return_view_without_credential_value() throws Exception {
        when(service.getForCurrentUser())
                .thenReturn(new GitConfigView("周锐", "zhourui@x.com", true, false));

        mvc.perform(get("/api/user/git-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("周锐"))
                .andExpect(jsonPath("$.email").value("zhourui@x.com"))
                .andExpect(jsonPath("$.credentialConfigured").value(true))
                .andExpect(jsonPath("$.readOnly").value(false))
                .andExpect(jsonPath("$.credPassword").doesNotExist())
                .andExpect(jsonPath("$.credUsername").doesNotExist());
    }

    @Test
    void get_default_user_should_report_read_only() throws Exception {
        when(service.getForCurrentUser()).thenReturn(GitConfigView.readOnly());

        mvc.perform(get("/api/user/git-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readOnly").value(true));
    }

    @Test
    void put_valid_should_save_and_return_success() throws Exception {
        String body = "{\"name\":\"周锐\",\"email\":\"zhourui@x.com\","
                + "\"credUsername\":\"gituser\",\"credPassword\":\"tok\"}";

        mvc.perform(put("/api/user/git-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(service).save(eq("周锐"), eq("zhourui@x.com"), eq("gituser"), eq("tok"));
    }

    @Test
    void put_by_default_user_should_return_409() throws Exception {
        doThrow(new IllegalStateException("系统默认用户不可修改"))
                .when(service).save(eq("n"), eq("a@b.com"), isNull(), isNull());
        String body = "{\"name\":\"n\",\"email\":\"a@b.com\"}";

        mvc.perform(put("/api/user/git-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void put_invalid_identity_should_return_400() throws Exception {
        doThrow(new IllegalArgumentException("invalid git identity email"))
                .when(service).save(eq("n"), eq("bad"), isNull(), isNull());
        String body = "{\"name\":\"n\",\"email\":\"bad\"}";

        mvc.perform(put("/api/user/git-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
