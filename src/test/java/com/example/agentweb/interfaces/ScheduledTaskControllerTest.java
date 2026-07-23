package com.example.agentweb.interfaces;

import com.example.agentweb.app.ScheduledTaskService;
import com.example.agentweb.domain.schedule.CronExpression;
import com.example.agentweb.domain.schedule.ScheduledTask;
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
import java.util.concurrent.Executor;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC slice test for {@link ScheduledTaskController}.
 *
 * <p>覆盖 CRUD 状态码、{@code @NotBlank} 校验、不存在任务的 400 异常映射、
 * 以及 {@code POST /run} 的异步触发契约。Cron 表达式合法性由领域 / Service 层校验,
 * 此处只验 HTTP 边界透传。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-26
 */
@WebMvcTest(ScheduledTaskController.class)
@Import(GlobalExceptionHandler.class)
class ScheduledTaskControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ScheduledTaskService taskService;

    @MockBean(name = "agentExecutor")
    private Executor agentExecutor;

    @MockBean
    private AuthProperties authProperties;

    /** SessionAuthFilter 构造依赖, 扫描 Filter Bean 时需补齐。 */


    /** SessionAuthFilter 身份决议统一走 AuthAppService, 切片测试放行所有请求。 */
    @MockBean
    private com.example.agentweb.app.auth.AuthAppService authAppService;


    @MockBean
    private com.example.agentweb.infra.auth.ThreadLocalUserContext userContext;

    /** SessionAuthFilter 构造依赖, @WebMvcTest 扫描 Filter Bean 时需补齐 */

    /** 手动登录链路依赖, SessionAuthFilter 现在还要 manual provider + props + repo, 切片测试一并 mock。 */
    @MockBean
    private com.example.agentweb.domain.auth.ManualSessionRepository manualSessionRepository;

    private ScheduledTask task(String id, String name, boolean enabled) {
        return ScheduledTask.restore(id, name, CronExpression.parse("0 0 * * * *"), "do something",
                "/tmp/work", enabled,
                Instant.parse("2026-05-26T08:00:00Z"),
                Instant.parse("2026-05-26T09:00:00Z"),
                null, null, null);
    }

    @Test
    void list_should_map_tasks_to_dto() throws Exception {
        when(taskService.listAll()).thenReturn(Arrays.asList(
                task("id-1", "daily", true),
                task("id-2", "weekly", false)));

        mvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("id-1"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[1].enabled").value(false));
    }

    @Test
    void create_should_return_dto_on_success() throws Exception {
        when(taskService.create("daily", "0 0 * * * *", "do x", "/tmp/work"))
                .thenReturn(task("id-1", "daily", true));

        String body = "{\"name\":\"daily\",\"cronExpr\":\"0 0 * * * *\","
                + "\"prompt\":\"do x\",\"workingDir\":\"/tmp/work\"}";

        mvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("id-1"))
                .andExpect(jsonPath("$.cronExpr").value("0 0 * * * *"));
    }

    @Test
    void create_should_return_400_when_cron_blank() throws Exception {
        String body = "{\"name\":\"daily\",\"cronExpr\":\"\",\"prompt\":\"x\",\"workingDir\":\"/w\"}";

        mvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation failed"));

        verify(taskService, never()).create(any(), any(), any(), any());
    }

    @Test
    void create_should_translate_domain_illegal_argument_to_400() throws Exception {
        when(taskService.create(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("invalid cron"));

        String body = "{\"name\":\"daily\",\"cronExpr\":\"bad\","
                + "\"prompt\":\"do x\",\"workingDir\":\"/tmp/work\"}";

        mvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("invalid cron")));
    }

    @Test
    void get_should_return_400_when_task_missing() throws Exception {
        when(taskService.getById("ghost")).thenReturn(null);

        mvc.perform(get("/api/tasks/ghost"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("ghost")));
    }

    @Test
    void get_should_return_dto_when_task_exists() throws Exception {
        when(taskService.getById("id-1")).thenReturn(task("id-1", "daily", true));

        mvc.perform(get("/api/tasks/id-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("id-1"))
                .andExpect(jsonPath("$.name").value("daily"));
    }

    @Test
    void update_should_passthrough_partial_fields() throws Exception {
        when(taskService.update("id-1", "renamed", null, null, null))
                .thenReturn(task("id-1", "renamed", true));

        String body = "{\"name\":\"renamed\"}";

        mvc.perform(put("/api/tasks/id-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("renamed"));
    }

    @Test
    void delete_should_return_success() throws Exception {
        mvc.perform(delete("/api/tasks/id-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(taskService, times(1)).delete("id-1");
    }

    @Test
    void toggle_should_invoke_service_and_return_refreshed_dto() throws Exception {
        when(taskService.getById("id-1")).thenReturn(task("id-1", "daily", false));

        mvc.perform(post("/api/tasks/id-1/toggle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        verify(taskService, times(1)).toggleEnabled("id-1");
    }

    @Test
    void run_should_dispatch_async_and_return_triggered() throws Exception {
        mvc.perform(post("/api/tasks/id-1/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message", containsString("已触发")));

        verify(agentExecutor, times(1)).execute(any(Runnable.class));
        verify(taskService, never()).executeTask(any());
    }
}
