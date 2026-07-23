package com.example.agentweb.interfaces;

import com.example.agentweb.app.refinery.RefineryRebuildService;
import com.example.agentweb.app.refinery.RebuildResult;
import com.example.agentweb.app.refinery.DiscardedRefinePage;
import com.example.agentweb.app.refinery.DiscardedRefineView;
import com.example.agentweb.app.refinery.RefineryAdminQueryService;
import com.example.agentweb.app.refinery.RefineryChunkPage;
import com.example.agentweb.app.refinery.RefineryChunkView;
import com.example.agentweb.app.refinery.RefineryDeleteResult;
import com.example.agentweb.infra.auth.AuthProperties;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC slice test for {@link RefineryAdminController}.
 *
 * <p>Controller 带 {@code @ConditionalOnProperty(agent.refinery.enabled=true)},
 * 故用 {@code @TestPropertySource} 打开开关才会装配。覆盖正常 200、busy 409、days 越界 422。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-31
 */
@WebMvcTest(RefineryAdminController.class)
@TestPropertySource(properties = "agent.refinery.enabled=true")
@Import(GlobalExceptionHandler.class)
class RefineryAdminControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private RefineryRebuildService rebuildService;

    @MockBean
    private RefineryAdminQueryService queryService;

    @MockBean
    private com.example.agentweb.app.refinery.RefineryAppService refineryAppService;

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

    @Test
    void rebuildRecent_default_should_return_200_with_summary() throws Exception {
        when(rebuildService.rebuildRecent(7)).thenReturn(RebuildResult.started(7, 3, 10));

        mvc.perform(post("/api/refinery/rebuild-recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.started").value(true))
                .andExpect(jsonPath("$.days").value(7))
                .andExpect(jsonPath("$.matchedSessions").value(3))
                .andExpect(jsonPath("$.chunksDeleted").value(10))
                .andExpect(jsonPath("$.queued").value(3));

        verify(rebuildService).rebuildRecent(7);
    }

    @Test
    void rebuildRecent_custom_days_should_passthrough() throws Exception {
        when(rebuildService.rebuildRecent(14)).thenReturn(RebuildResult.started(14, 0, 0));

        mvc.perform(post("/api/refinery/rebuild-recent").param("days", "14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(14));

        verify(rebuildService).rebuildRecent(14);
    }

    @Test
    void rebuildRecent_busy_should_return_409() throws Exception {
        when(rebuildService.rebuildRecent(7)).thenReturn(RebuildResult.busy(7, 2, 0));

        mvc.perform(post("/api/refinery/rebuild-recent"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.started").value(false))
                .andExpect(jsonPath("$.reason").value("rebuild-in-progress"));
    }

    @Test
    void rebuildRecent_days_too_small_should_return_422_without_calling_service() throws Exception {
        mvc.perform(post("/api/refinery/rebuild-recent").param("days", "0"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.message").value("days 必须在 [1,90] 范围内"))
                .andExpect(jsonPath("$.days").value(0));

        verify(rebuildService, never()).rebuildRecent(anyInt());
    }

    @Test
    void rebuildRecent_days_too_large_should_return_422() throws Exception {
        mvc.perform(post("/api/refinery/rebuild-recent").param("days", "91"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("validation_failed"));

        verify(rebuildService, never()).rebuildRecent(anyInt());
    }

    @Test
    void listChunks_default_should_return_200_with_page_and_active_status() throws Exception {
        when(queryService.findChunks(1, 20, "all")).thenReturn(new RefineryChunkPage(
                Collections.singletonList(chunk("c1", RefineryChunkView.STATUS_ACTIVE)), 1L, 1, 20));

        mvc.perform(get("/api/refinery/chunks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items[0].id").value("c1"))
                .andExpect(jsonPath("$.items[0].title").value("标题"))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"));
    }

    @Test
    void listChunks_status_active_should_passthrough_activeOnly() throws Exception {
        when(queryService.findChunks(1, 20, "active"))
                .thenReturn(new RefineryChunkPage(Collections.emptyList(), 0L, 1, 20));

        mvc.perform(get("/api/refinery/chunks").param("status", "active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        verify(queryService).findChunks(1, 20, "active");
    }

    @Test
    void listChunks_size_over_max_should_clamp_to_100() throws Exception {
        when(queryService.findChunks(1, 999, "all"))
                .thenReturn(new RefineryChunkPage(Collections.emptyList(), 0L, 1, 100));

        mvc.perform(get("/api/refinery/chunks").param("size", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));

        verify(queryService).findChunks(1, 999, "all");
    }

    @Test
    void listChunks_archived_chunk_status_should_be_ARCHIVED() throws Exception {
        when(queryService.findChunks(1, 20, "all")).thenReturn(new RefineryChunkPage(
                Collections.singletonList(chunk("c-arch", RefineryChunkView.STATUS_ARCHIVED)),
                1L, 1, 20));

        mvc.perform(get("/api/refinery/chunks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("ARCHIVED"));
    }

    @Test
    void deleteChunk_hit_should_return_200_with_deleted_true() throws Exception {
        when(refineryAppService.deleteChunk("c1")).thenReturn(new RefineryDeleteResult("c1", true));

        mvc.perform(delete("/api/refinery/chunks/{id}", "c1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("c1"))
                .andExpect(jsonPath("$.deleted").value(true));

        verify(refineryAppService).deleteChunk("c1");
    }

    @Test
    void deleteChunk_miss_should_return_404_with_deleted_false() throws Exception {
        when(refineryAppService.deleteChunk("missing")).thenReturn(new RefineryDeleteResult("missing", false));

        mvc.perform(delete("/api/refinery/chunks/{id}", "missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.deleted").value(false));
    }

    @Test
    void listDiscarded_default_should_return_200_with_page_and_DISCARDED_status() throws Exception {
        when(queryService.findDiscarded(1, 20)).thenReturn(new DiscardedRefinePage(
                Collections.singletonList(discarded("d1")), 1L, 1, 20));

        mvc.perform(get("/api/refinery/discarded"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items[0].id").value("d1"))
                .andExpect(jsonPath("$.items[0].title").value("低分标题"))
                .andExpect(jsonPath("$.items[0].score").value(0.3))
                .andExpect(jsonPath("$.items[0].threshold").value(0.5))
                .andExpect(jsonPath("$.items[0].status").value("DISCARDED"));
    }

    @Test
    void listDiscarded_size_over_max_should_clamp_to_100() throws Exception {
        when(queryService.findDiscarded(1, 999))
                .thenReturn(new DiscardedRefinePage(Collections.emptyList(), 0L, 1, 100));

        mvc.perform(get("/api/refinery/discarded").param("size", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));

        verify(queryService).findDiscarded(1, 999);
    }

    @Test
    void deleteDiscarded_hit_should_return_200_with_deleted_true() throws Exception {
        when(refineryAppService.deleteDiscarded("d1")).thenReturn(new RefineryDeleteResult("d1", true));

        mvc.perform(delete("/api/refinery/discarded/{id}", "d1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("d1"))
                .andExpect(jsonPath("$.deleted").value(true));

        verify(refineryAppService).deleteDiscarded("d1");
    }

    @Test
    void deleteDiscarded_miss_should_return_404_with_deleted_false() throws Exception {
        when(refineryAppService.deleteDiscarded("missing"))
                .thenReturn(new RefineryDeleteResult("missing", false));

        mvc.perform(delete("/api/refinery/discarded/{id}", "missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.deleted").value(false));
    }

    private static DiscardedRefineView discarded(String id) {
        return new DiscardedRefineView(id, "低分标题", 0.3d, 0.5d, "GENERAL", "结论",
                "CHAT", "sess-1", "CLAUDE", "test", "2026-06-04T00:00:00Z",
                "score below threshold", DiscardedRefineView.STATUS_DISCARDED);
    }

    private static RefineryChunkView chunk(String id, String status) {
        return new RefineryChunkView(id, "标题", 0.8d, "BUSINESS", "结论",
                Collections.singletonList("信号1"), "sess-1", "msg_1..msg_3", "CLAUDE",
                "2026-06-01T00:00:00Z", "2099-01-01T00:00:00Z", null, status);
    }

    @org.junit.jupiter.api.Test
    void reembed_should_return_refreshed_count_and_reject_invalid_limit() throws Exception {
        when(refineryAppService.reembedActive(100)).thenReturn(7);

        mvc.perform(post("/api/refinery/reembed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshed").value(7));

        mvc.perform(post("/api/refinery/reembed?limit=0"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.message").value("limit 必须在 [1,1000] 范围内"));
        verify(refineryAppService, never()).reembedActive(0);
    }
}
