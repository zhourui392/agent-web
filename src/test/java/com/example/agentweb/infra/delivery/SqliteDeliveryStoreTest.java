package com.example.agentweb.infra.delivery;

import com.example.agentweb.domain.delivery.MergeRequestRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交付三表仓储轻集成(@TempDir + SQLiteDataSource,不起 Spring):
 * merge_request_ref upsert 幂等、processed_webhook 去重/清理、requirement_intake_dedup 幂等录入。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class SqliteDeliveryStoreTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteDeliveryStore store;

    @BeforeEach
    public void setUp() {
        File dbFile = tempDir.resolve("delivery-test.db").toFile();
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        jdbc = new JdbcTemplate(ds);
        DeliveryTestSchema.create(jdbc);
        store = new SqliteDeliveryStore(jdbc);
    }

    // ---- merge_request_ref ----

    @Test
    public void upsert_should_insert_new_row_and_round_trip_fields() {
        // given
        MergeRequestRef ref = new MergeRequestRef(42, "http://gitlab.test/g/p/-/merge_requests/42", true, null);

        // when
        store.upsert("R2607040001", ref);

        // then
        List<MergeRequestRef> found = store.findByRequirementId("R2607040001");
        assertEquals(1, found.size());
        assertEquals(42, found.get(0).getMrIid());
        assertEquals("http://gitlab.test/g/p/-/merge_requests/42", found.get(0).getUrl());
        assertTrue(found.get(0).isDraft());
        assertNull(found.get(0).getPipelineStatus());
    }

    @Test
    public void upsert_same_key_twice_should_update_fields_without_adding_row() {
        // given
        store.upsert("R2607040001", new MergeRequestRef(42, "http://old", true, null));

        // when: 同 (requirement_id, mr_iid) 二次 upsert
        store.upsert("R2607040001", new MergeRequestRef(42, "http://new", false, "success"));

        // then: 行数不变,字段更新
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM merge_request_ref", Integer.class);
        assertEquals(1, count);
        MergeRequestRef loaded = store.findByRequirementId("R2607040001").get(0);
        assertEquals("http://new", loaded.getUrl());
        assertFalse(loaded.isDraft());
        assertEquals("success", loaded.getPipelineStatus());
    }

    @Test
    public void findByRequirementId_should_order_by_mr_iid_ascending() {
        // given: 乱序写入
        store.upsert("R2607040001", new MergeRequestRef(7, "http://mr/7", true, null));
        store.upsert("R2607040001", new MergeRequestRef(3, "http://mr/3", true, null));
        store.upsert("R2607040002", new MergeRequestRef(1, "http://mr/1", true, null));

        // when
        List<MergeRequestRef> found = store.findByRequirementId("R2607040001");

        // then: 只含本需求且按 iid 升序
        assertEquals(2, found.size());
        assertEquals(3, found.get(0).getMrIid());
        assertEquals(7, found.get(1).getMrIid());
    }

    @Test
    public void findByRequirementId_miss_should_return_empty_list() {
        assertTrue(store.findByRequirementId("R-none").isEmpty());
    }

    // ---- processed_webhook ----

    @Test
    public void tryMarkProcessed_first_time_should_return_true_duplicate_false() {
        Instant now = Instant.parse("2026-07-04T00:00:00Z");

        assertTrue(store.tryMarkProcessed("uuid-a", now));
        assertFalse(store.tryMarkProcessed("uuid-a", now.plusSeconds(5)));
    }

    @Test
    public void purgeBefore_should_delete_only_rows_before_cutoff() {
        // given
        Instant cutoff = Instant.parse("2026-07-04T00:00:00Z");
        store.tryMarkProcessed("uuid-old", cutoff.minusSeconds(1));
        store.tryMarkProcessed("uuid-new", cutoff);

        // when
        int purged = store.purgeBefore(cutoff);

        // then: 严格小于 cutoff 的删掉,老 uuid 可重新标记,边界行保留
        assertEquals(1, purged);
        assertTrue(store.tryMarkProcessed("uuid-old", cutoff.plusSeconds(1)));
        assertFalse(store.tryMarkProcessed("uuid-new", cutoff.plusSeconds(1)));
    }

    // ---- requirement_intake_dedup ----

    @Test
    public void findRequirementId_miss_should_return_empty() {
        assertTrue(store.findRequirementId("key-a", "idem-1").isEmpty());
    }

    @Test
    public void record_then_find_should_return_requirement_id() {
        // given
        store.record("key-a", "idem-1", "R2607040001", Instant.parse("2026-07-04T00:00:00Z"));

        // then
        assertEquals("R2607040001", store.findRequirementId("key-a", "idem-1").orElseThrow());
        assertTrue(store.findRequirementId("key-b", "idem-1").isEmpty());
    }

    @Test
    public void record_duplicate_key_should_keep_first_value_silently() {
        // given
        store.record("key-a", "idem-1", "R2607040001", Instant.parse("2026-07-04T00:00:00Z"));

        // when: 同 key 重复 record 不抛异常
        store.record("key-a", "idem-1", "R2607049999", Instant.parse("2026-07-04T01:00:00Z"));

        // then: 首次值不被覆盖
        assertEquals("R2607040001", store.findRequirementId("key-a", "idem-1").orElseThrow());
    }
}
