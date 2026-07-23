package com.example.agentweb.interfaces;

import com.example.agentweb.app.refinery.DiscardedRefinePage;
import com.example.agentweb.app.refinery.RefineryAdminQueryService;
import com.example.agentweb.app.refinery.RefineryAppService;
import com.example.agentweb.app.refinery.RefineryChunkPage;
import com.example.agentweb.app.refinery.RefineryDeleteResult;
import com.example.agentweb.app.refinery.RefineryRebuildService;
import com.example.agentweb.app.refinery.RebuildResult;
import com.example.agentweb.interfaces.dto.ChatRagChunkPageResponse;
import com.example.agentweb.interfaces.dto.DiscardedRecordPageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Knowledge Refinery 管理接口(仅供 Web 前端使用)。
 *
 * <p>路径前缀 {@code /api/refinery} 走普通用户会话鉴权。
 * 仅在 {@code agent.refinery.enabled=true} 时装配，
 * 否则依赖 {@link RefineryRebuildService} 缺失会导致启动失败。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-31
 */
@RestController
@RequestMapping(path = "/api/refinery", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "agent.refinery", name = "enabled", havingValue = "true")
@Slf4j
@Validated
public class RefineryAdminController {

    private static final int DEFAULT_DAYS = 7;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final RefineryRebuildService rebuildService;
    private final RefineryAdminQueryService queryService;
    private final RefineryAppService refineryAppService;

    public RefineryAdminController(RefineryRebuildService rebuildService,
                                   RefineryAdminQueryService queryService,
                                   RefineryAppService refineryAppService) {
        this.rebuildService = rebuildService;
        this.queryService = queryService;
        this.refineryAppService = refineryAppService;
    }

    /**
     * 存量 chunk 重嵌入（M4 triggerDescription 迁移）：按批刷新活跃 chunk 向量，管理台手动分批触发。
     *
     * @param limit 本批上限, 默认 100, 范围 [1,1000]
     */
    @PostMapping("/reembed")
    public ResponseEntity<Object> reembed(
            @RequestParam(value = "limit", defaultValue = "100")
            @Min(value = 1, message = "limit 必须在 [1,1000] 范围内")
            @Max(value = 1000, message = "limit 必须在 [1,1000] 范围内") int limit) {
        int refreshed = refineryAppService.reembedActive(limit);
        Map<String, Object> body = new HashMap<>(2);
        body.put("refreshed", refreshed);
        return ResponseEntity.ok(body);
    }

    /**
     * 清空 last_message_at 在最近 {@code days} 天内的会话的 RAG 数据(chunk + 幂等 state),
     * 并后台串行重跑 refine+ingest。只清 RAG 数据, 不动会话本体与消息。
     *
     * @param days 回溯天数, 默认 7, 范围 [1,90]
     */
    @PostMapping("/rebuild-recent")
    public ResponseEntity<Object> rebuildRecent(
            @RequestParam(value = "days", defaultValue = "" + DEFAULT_DAYS)
            @Min(value = 1, message = "days 必须在 [1,90] 范围内")
            @Max(value = 90, message = "days 必须在 [1,90] 范围内") int days) {
        log.info("refinery-rebuild-request days={}", days);
        RebuildResult result = rebuildService.rebuildRecent(days);
        if (!result.isStarted()) {
            return ResponseEntity.status(409).body(result);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 分页列出召回库存. {@code status=active} 只看可召回 (未归档未过期), 其余值看全部;
     * 每项另带实时算出的 {@code status} (ACTIVE/ARCHIVED) 供前端"状态"列展示。
     *
     * @param page   页码, 从 1 起, 越界归一到 1
     * @param size   每页条数, clamp 到 [1,100]
     * @param status {@code active}=仅可召回; 其余 (默认 all)=全部
     */
    @GetMapping("/chunks")
    public ResponseEntity<ChatRagChunkPageResponse> listChunks(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
            @RequestParam(value = "status", defaultValue = "all") String status) {
        RefineryChunkPage result = queryService.findChunks(page, size, status);
        return ResponseEntity.ok(ChatRagChunkPageResponse.from(result));
    }

    /**
     * 硬删除单条召回 chunk(管理台"召回历史"逐条删除)。命中返回 200, 未找到返回 404。
     *
     * @param id chunk 主键
     */
    @DeleteMapping("/chunks/{id}")
    public ResponseEntity<Object> deleteChunk(@PathVariable("id") String id) {
        RefineryDeleteResult result = refineryAppService.deleteChunk(id);
        log.info("refinery-chunk-delete-request id={} deleted={}", id, result.deleted());
        return result.deleted()
                ? ResponseEntity.ok(result)
                : ResponseEntity.status(404).body(result);
    }

    /**
     * 分页列出 below-threshold(评分 &lt; score-threshold) 被丢弃的会话留痕,
     * 供管理台"已丢弃(低分)"展示与阈值校准。按 created_at 倒序。
     *
     * @param page 页码, 从 1 起, 越界归一到 1
     * @param size 每页条数, clamp 到 [1,100]
     */
    @GetMapping("/discarded")
    public ResponseEntity<DiscardedRecordPageResponse> listDiscarded(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        DiscardedRefinePage result = queryService.findDiscarded(page, size);
        return ResponseEntity.ok(DiscardedRecordPageResponse.from(result));
    }

    /**
     * 硬删除单条丢弃记录。命中返回 200, 未找到返回 404。
     *
     * @param id 丢弃记录主键
     */
    @DeleteMapping("/discarded/{id}")
    public ResponseEntity<Object> deleteDiscarded(@PathVariable("id") String id) {
        RefineryDeleteResult result = refineryAppService.deleteDiscarded(id);
        log.info("refinery-discarded-delete-request id={} deleted={}", id, result.deleted());
        return result.deleted()
                ? ResponseEntity.ok(result)
                : ResponseEntity.status(404).body(result);
    }
}
