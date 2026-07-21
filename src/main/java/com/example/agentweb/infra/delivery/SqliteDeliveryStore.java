package com.example.agentweb.infra.delivery;

import com.example.agentweb.app.delivery.MergeRequestStore;
import com.example.agentweb.app.delivery.WebhookDedupStore;
import com.example.agentweb.app.requirement.RequirementIdempotencyStore;
import com.example.agentweb.domain.delivery.MergeRequestRef;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 交付三表(merge_request_ref / processed_webhook / requirement_intake_dedup)的 SQLite 实现。
 *
 * <p>幂等写一律走 SQLite 原生 upsert / INSERT OR IGNORE:无 Spring 上下文时 PK 冲突不会被翻译成
 * DuplicateKeyException(是 error code 19 的 UncategorizedSQLException),catch 方案不可靠。
 * 时间列统一 epoch millis。装配由 @Configuration 负责,本类不带 Spring 注解。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class SqliteDeliveryStore implements MergeRequestStore, WebhookDedupStore, RequirementIdempotencyStore {

    private final JdbcTemplate jdbc;

    public SqliteDeliveryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- MergeRequestStore ----

    /**
     * 落库 MR 引用;同 (requirement_id, mr_iid) 二次写入按 upsert 更新,消化 webhook 重放。
     *
     * @param requirementId 需求 ID
     * @param ref           MR 引用
     */
    @Override
    public void upsert(String requirementId, MergeRequestRef ref) {
        jdbc.update("INSERT INTO merge_request_ref"
                        + " (requirement_id, mr_iid, mr_url, draft, pipeline_status, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT(requirement_id, mr_iid) DO UPDATE SET"
                        + " mr_url = excluded.mr_url, draft = excluded.draft,"
                        + " pipeline_status = excluded.pipeline_status, updated_at = excluded.updated_at",
                requirementId, ref.getMrIid(), ref.getUrl(), ref.isDraft() ? 1 : 0,
                ref.getPipelineStatus(), Instant.now().toEpochMilli());
    }

    /**
     * 查询某需求全部 MR 引用。
     *
     * @param requirementId 需求 ID
     * @return 按 mr_iid 升序;无记录时空列表
     */
    @Override
    public List<MergeRequestRef> findByRequirementId(String requirementId) {
        return jdbc.query("SELECT mr_iid, mr_url, draft, pipeline_status FROM merge_request_ref"
                        + " WHERE requirement_id = ? ORDER BY mr_iid ASC",
                (rs, rowNum) -> new MergeRequestRef(rs.getLong("mr_iid"), rs.getString("mr_url"),
                        rs.getInt("draft") == 1, rs.getString("pipeline_status")),
                requirementId);
    }

    // ---- WebhookDedupStore ----

    /**
     * 尝试标记 webhook 事件已处理。
     *
     * @param eventUuid  X-Gitlab-Event-UUID
     * @param receivedAt 收到时间
     * @return true=首次处理; false=重复事件
     */
    @Override
    public boolean tryMarkProcessed(String eventUuid, Instant receivedAt) {
        int affected = jdbc.update(
                "INSERT OR IGNORE INTO processed_webhook (event_uuid, received_at) VALUES (?, ?)",
                eventUuid, receivedAt.toEpochMilli());
        return affected == 1;
    }

    /**
     * 删除 cutoff 之前(严格小于)的去重记录。
     *
     * @param cutoff 截止时间
     * @return 删除行数
     */
    @Override
    public int purgeBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM processed_webhook WHERE received_at < ?", cutoff.toEpochMilli());
    }

    // ---- RequirementIdempotencyStore ----

    /**
     * 按 (apiKeyName, idempotencyKey) 查既有需求 ID。
     *
     * @param apiKeyName     API key 名
     * @param idempotencyKey Idempotency-Key
     * @return 命中返回需求 ID,否则 empty
     */
    @Override
    public Optional<String> findRequirementId(String apiKeyName, String idempotencyKey) {
        List<String> found = jdbc.queryForList(
                "SELECT requirement_id FROM requirement_intake_dedup WHERE api_key_name = ? AND idem_key = ?",
                String.class, apiKeyName, idempotencyKey);
        return found.stream().findFirst();
    }

    /**
     * 记录幂等映射;同 key 重复 record 幂等静默(保留首次值)。
     *
     * @param apiKeyName     API key 名
     * @param idempotencyKey Idempotency-Key
     * @param requirementId  需求 ID
     * @param at             记录时间
     */
    @Override
    public void record(String apiKeyName, String idempotencyKey, String requirementId, Instant at) {
        jdbc.update("INSERT OR IGNORE INTO requirement_intake_dedup"
                        + " (api_key_name, idem_key, requirement_id, created_at) VALUES (?, ?, ?, ?)",
                apiKeyName, idempotencyKey, requirementId, at.toEpochMilli());
    }
}
