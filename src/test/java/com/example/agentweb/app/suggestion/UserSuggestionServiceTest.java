package com.example.agentweb.app.suggestion;

import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.auth.UserContext;
import com.example.agentweb.domain.suggestion.UserSuggestion;
import com.example.agentweb.domain.suggestion.UserSuggestionPage;
import com.example.agentweb.domain.suggestion.UserSuggestionRepository;
import com.example.agentweb.domain.suggestion.UserSuggestionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link UserSuggestionService} 应用服务测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public class UserSuggestionServiceTest {

    private UserSuggestionRepository repo;
    private StubUserContext userContext;
    private UserSuggestionService service;

    private static final class StubUserContext implements UserContext {
        private String userId;
        private String userName;

        @Override
        public Optional<LoginUser> currentUser() {
            return userId == null ? Optional.empty() : Optional.of(new LoginUser(userId, userName, null));
        }
    }

    @BeforeEach
    public void setUp() {
        repo = mock(UserSuggestionRepository.class);
        userContext = new StubUserContext();
        userContext.userId = "u1";
        userContext.userName = "张三";
        service = new UserSuggestionService(repo, new CurrentUserProvider(userContext));
    }

    @Test
    public void submit_should_attach_current_user_and_pending_status() {
        UserSuggestion created = service.submit("标题", "内容", "wx");

        assertEquals("u1", created.getUserId());
        assertEquals("张三", created.getUserName());
        assertEquals("标题", created.getTitle());
        assertEquals(UserSuggestionStatus.PENDING, created.getStatus());
        verify(repo).save(any(UserSuggestion.class));
    }

    @Test
    public void listMine_should_query_current_user_with_clamped_limit() {
        when(repo.findByUserId(eq("u1"), eq(100))).thenReturn(Collections.emptyList());

        service.listMine(999);

        verify(repo).findByUserId("u1", 100);
    }

    @Test
    public void getMine_should_reject_other_user_suggestion_when_filter_enabled() {
        when(repo.findById("s2")).thenReturn(item("s2", "u2"));

        assertThrows(IllegalArgumentException.class, () -> service.getMine("s2"));
    }

    @Test
    public void getMine_should_allow_owner_suggestion() {
        when(repo.findById("s1")).thenReturn(item("s1", "u1"));

        assertEquals("s1", service.getMine("s1").getId());
    }

    @Test
    public void listForAdmin_should_parse_status_and_clamp_page_size() {
        when(repo.findPage(eq(UserSuggestionStatus.PENDING), eq("foo"), eq(1), eq(100)))
                .thenReturn(new UserSuggestionPage(Collections.emptyList(), 0L, 1, 100));

        service.listForAdmin("pending", "foo", -1, 999);

        verify(repo).findPage(UserSuggestionStatus.PENDING, "foo", 1, 100);
    }

    @Test
    public void updateByAdmin_should_persist_status_and_reply() {
        when(repo.findById("s1")).thenReturn(item("s1", "u1"));

        UserSuggestion updated = service.updateByAdmin("s1", "REPLIED", "已处理");

        assertEquals(UserSuggestionStatus.REPLIED, updated.getStatus());
        assertEquals("已处理", updated.getAdminReply());
        verify(repo).save(any(UserSuggestion.class));
    }

    private UserSuggestion item(String id, String userId) {
        Instant now = Instant.parse("2026-06-11T08:00:00Z");
        return new UserSuggestion(id, userId, "name", "title", "content", null,
                UserSuggestionStatus.PENDING, null, now, now, null);
    }
}
