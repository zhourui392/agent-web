package com.example.agentweb.interfaces;

import com.example.agentweb.app.auth.AdminUserAppService;
import com.example.agentweb.app.auth.AdminUserView;
import com.example.agentweb.domain.auth.UserRole;
import com.example.agentweb.domain.auth.UsernameAlreadyExistsException;
import com.example.agentweb.infra.auth.AuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Arrays;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AdminUserController} 管理员用户接口契约测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@WebMvcTest(AdminUserController.class)
@Import(GlobalExceptionHandler.class)
class AdminUserControllerTest {

    private static final Instant NOW = Instant.parse("2026-07-22T08:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockBean
    private AdminUserAppService appService;

    @MockBean
    private AuthProperties authProperties;

    @MockBean
    private com.example.agentweb.app.auth.AuthAppService authAppService;

    @MockBean
    private com.example.agentweb.infra.auth.ThreadLocalUserContext userContext;

    @MockBean
    private com.example.agentweb.domain.auth.ManualSessionRepository manualSessionRepository;

    @Test
    void list_should_ReturnSafeUserViews() throws Exception {
        when(appService.listUsers()).thenReturn(Arrays.asList(
                new AdminUserView("admin", "admin", UserRole.ADMIN, true, NOW, NOW)));

        mvc.perform(get("/api/admin-users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("admin"))
                .andExpect(jsonPath("$[0].username").value("admin"))
                .andExpect(jsonPath("$[0].role").value("ADMIN"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
    }

    @Test
    void create_should_ReturnCreatedUserWithoutPassword() throws Exception {
        AdminUserView created = new AdminUserView(
                "user-id", "zhangsan", UserRole.USER, true, NOW, NOW);
        when(appService.createUser(
                "zhangsan", "A-secure-password!2026", UserRole.USER)).thenReturn(created);

        mvc.perform(post("/api/admin-users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"zhangsan\","
                                + "\"password\":\"A-secure-password!2026\",\"role\":\"USER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("user-id"))
                .andExpect(jsonPath("$.username").value("zhangsan"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        verify(appService).createUser(
                "zhangsan", "A-secure-password!2026", UserRole.USER);
    }

    @Test
    void create_should_ReturnBadRequest_WhenPayloadViolatesAccountContract() throws Exception {
        mvc.perform(post("/api/admin-users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"short\",\"role\":null}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_should_ReturnConflict_WhenUsernameAlreadyExists() throws Exception {
        when(appService.createUser(
                "admin", "A-secure-password!2026", UserRole.USER))
                .thenThrow(new UsernameAlreadyExistsException());

        mvc.perform(post("/api/admin-users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\","
                                + "\"password\":\"A-secure-password!2026\",\"role\":\"USER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("用户名已存在"));
    }
}
