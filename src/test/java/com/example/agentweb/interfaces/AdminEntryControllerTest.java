package com.example.agentweb.interfaces;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AdminEntryController} 入口重定向单测:/admin → /admin/dashboard.html,
 * 共享域名 /qa 部署(context-path 挂载)下必须补回 /qa 前缀(sendRedirect 的根相对路径不会
 * 自动加 contextPath),否则浏览器跟随后丢 /qa 落根域进不去管理页。
 *
 * @author zhourui(V33215020)
 */
class AdminEntryControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AdminEntryController()).build();
    }

    /** 根部署(无挂载前缀):重定向到根相对 /admin/dashboard.html。 */
    @Test
    void admin_should_redirect_to_dashboard_without_prefix() throws Exception {
        mvc.perform(get("/admin"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/dashboard.html"));
    }

    /** 共享域名 /qa 部署:重定向目标必须补回 /qa 前缀,浏览器才落回 /qa/admin/dashboard.html。 */
    @Test
    void admin_should_prepend_context_prefix() throws Exception {
        mvc.perform(get("/qa/admin").contextPath("/qa"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/qa/admin/dashboard.html"));
    }

    /** 带尾斜杠的 /admin/ 入口同样补前缀。 */
    @Test
    void admin_slash_should_prepend_context_prefix() throws Exception {
        mvc.perform(get("/qa/admin/").contextPath("/qa"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/qa/admin/dashboard.html"));
    }
}
