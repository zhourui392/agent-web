package com.example.agentweb.infra.suggestion;

import com.example.agentweb.domain.suggestion.UserSuggestion;
import com.example.agentweb.domain.suggestion.UserSuggestionPage;
import com.example.agentweb.domain.suggestion.UserSuggestionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link SqliteUserSuggestionRepo} 轻量集成测试:真实 SQLite + @TempDir。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public class SqliteUserSuggestionRepoTest {

    @TempDir
    Path tempDir;

    private SqliteUserSuggestionRepo repo;

    @BeforeEach
    public void setUp() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("suggestion-test.db"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE user_suggestion ("
                + "id TEXT PRIMARY KEY,"
                + "user_id TEXT,"
                + "user_name TEXT,"
                + "title TEXT,"
                + "content TEXT NOT NULL,"
                + "contact TEXT,"
                + "status TEXT NOT NULL,"
                + "admin_reply TEXT,"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL,"
                + "replied_at INTEGER)");
        repo = new SqliteUserSuggestionRepo(jdbc);
    }

    @Test
    public void save_findById_should_roundtrip_all_fields() {
        UserSuggestion suggestion = new UserSuggestion(
                "s1", "u1", "张三", "标题", "内容", "wx",
                UserSuggestionStatus.REPLIED, "已处理",
                Instant.parse("2026-06-11T08:00:00Z"),
                Instant.parse("2026-06-11T09:00:00Z"),
                Instant.parse("2026-06-11T09:10:00Z"));

        repo.save(suggestion);
        UserSuggestion loaded = repo.findById("s1");

        assertEquals("s1", loaded.getId());
        assertEquals("u1", loaded.getUserId());
        assertEquals("张三", loaded.getUserName());
        assertEquals("标题", loaded.getTitle());
        assertEquals("内容", loaded.getContent());
        assertEquals("wx", loaded.getContact());
        assertEquals(UserSuggestionStatus.REPLIED, loaded.getStatus());
        assertEquals("已处理", loaded.getAdminReply());
        assertEquals(Instant.parse("2026-06-11T08:00:00Z"), loaded.getCreatedAt());
        assertEquals(Instant.parse("2026-06-11T09:10:00Z"), loaded.getRepliedAt());
    }

    @Test
    public void findByUserId_should_only_return_that_user_in_updated_desc_order() {
        repo.save(item("s1", "u1", "old", "2026-06-11T08:00:00Z", UserSuggestionStatus.PENDING));
        repo.save(item("s2", "u2", "other", "2026-06-11T10:00:00Z", UserSuggestionStatus.PENDING));
        repo.save(item("s3", "u1", "new", "2026-06-11T12:00:00Z", UserSuggestionStatus.REPLIED));

        List<UserSuggestion> rows = repo.findByUserId("u1", 10);

        assertEquals(2, rows.size());
        assertEquals("s3", rows.get(0).getId());
        assertEquals("s1", rows.get(1).getId());
    }

    @Test
    public void findPage_should_filter_by_status_and_keyword() {
        repo.save(item("s1", "u1", "登录建议", "2026-06-11T08:00:00Z", UserSuggestionStatus.PENDING));
        repo.save(item("s2", "u2", "页面建议", "2026-06-11T09:00:00Z", UserSuggestionStatus.REPLIED));
        repo.save(item("s3", "u3", "按钮问题", "2026-06-11T10:00:00Z", UserSuggestionStatus.PENDING));

        UserSuggestionPage page = repo.findPage(UserSuggestionStatus.PENDING, "建议", 1, 10);

        assertEquals(1, page.getTotal());
        assertEquals("s1", page.getRows().get(0).getId());
    }

    @Test
    public void save_same_id_should_replace() {
        repo.save(item("s1", "u1", "旧", "2026-06-11T08:00:00Z", UserSuggestionStatus.PENDING));
        repo.save(item("s1", "u1", "新", "2026-06-11T09:00:00Z", UserSuggestionStatus.CLOSED));

        UserSuggestion loaded = repo.findById("s1");

        assertEquals("新", loaded.getTitle());
        assertEquals(UserSuggestionStatus.CLOSED, loaded.getStatus());
        assertNull(repo.findById("missing"));
    }

    private UserSuggestion item(String id, String userId, String title, String updatedAt,
                                UserSuggestionStatus status) {
        Instant created = Instant.parse("2026-06-11T07:00:00Z");
        return new UserSuggestion(id, userId, userId + "Name", title, title + "内容", null,
                status, null, created, Instant.parse(updatedAt), null);
    }
}
