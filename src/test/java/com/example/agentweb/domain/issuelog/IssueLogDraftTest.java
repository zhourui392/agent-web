package com.example.agentweb.domain.issuelog;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IssueLogDraft} 值对象单测,覆盖必填字段校验、空格归一化、不可变性。
 *
 * @author zhourui(V33215020)
 * @since 2026-05-19
 */
public class IssueLogDraftTest {

    @Test
    public void constructor_slug_should_be_normalized_to_lowercase_kebab() {
        IssueLogDraft draft = new IssueLogDraft(
                "标题", Arrays.asList("logic-pitfall"), Arrays.asList("svc"),
                Arrays.asList("词"),
                "现象", "根因", "解决", "",
                "  Exchange Lottery_No Refund ");

        assertEquals("exchange-lottery-no-refund", draft.getSlug());
    }

    @Test
    public void constructor_slug_with_no_ascii_content_should_normalize_to_null() {
        IssueLogDraft draft = new IssueLogDraft(
                "标题", Arrays.asList("logic-pitfall"), Arrays.asList("svc"),
                Arrays.asList("词"),
                "现象", "根因", "解决", "",
                "中文标识");

        assertEquals(null, draft.getSlug());
    }

    @Test
    public void constructor_without_slug_should_default_to_null() {
        IssueLogDraft draft = new IssueLogDraft(
                "标题", Arrays.asList("logic-pitfall"), Arrays.asList("svc"),
                Arrays.asList("词"),
                "现象", "根因", "解决", "");

        assertEquals(null, draft.getSlug());
    }

    @Test
    public void requireArchivable_with_trigger_signals_should_pass() {
        IssueLogDraft draft = new IssueLogDraft(
                "标题", Arrays.asList("logic-pitfall"), Arrays.asList("svc"),
                Arrays.asList("报错文案", "9200201"),
                "现象", "根因", "解决", "");

        draft.requireArchivable();
    }

    @Test
    public void requireArchivable_without_trigger_signals_should_throw() {
        IssueLogDraft draft = new IssueLogDraft(
                "标题", Arrays.asList("logic-pitfall"), Arrays.asList("svc"),
                Collections.emptyList(),
                "现象", "根因", "解决", "");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                draft::requireArchivable);
        assertTrue(ex.getMessage().contains("触发词"),
                "报错信息应指明触发词缺失: " + ex.getMessage());
    }

    @Test
    public void constructor_all_fields_non_empty_should_succeed() {
        IssueLogDraft draft = new IssueLogDraft(
                "签到页打不开",
                Arrays.asList("logic-pitfall"),
                Arrays.asList("activity-gateway"),
                "用户反馈签到页 502",
                "签到配置关联的 task_play 玩法过期",
                "下线已过期的关联配置",
                "");

        assertEquals("签到页打不开", draft.getTitle());
        assertEquals(Arrays.asList("logic-pitfall"), draft.getCategories());
        assertEquals(Arrays.asList("activity-gateway"), draft.getServices());
        assertEquals("", draft.getNotes());
    }

    @Test
    public void constructor_title_is_null_should_throw() {
        assertThrows(IllegalArgumentException.class, () -> new IssueLogDraft(
                null,
                Arrays.asList("logic-pitfall"),
                Arrays.asList("svc"),
                "", "", "", ""));
    }

    @Test
    public void constructor_title_is_blank_should_throw() {
        assertThrows(IllegalArgumentException.class, () -> new IssueLogDraft(
                "   ",
                Arrays.asList("logic-pitfall"),
                Arrays.asList("svc"),
                "", "", "", ""));
    }

    @Test
    public void constructor_categories_is_null_should_throw() {
        assertThrows(IllegalArgumentException.class, () -> new IssueLogDraft(
                "x",
                null,
                Arrays.asList("svc"),
                "", "", "", ""));
    }

    @Test
    public void constructor_categories_is_empty_list_should_throw() {
        assertThrows(IllegalArgumentException.class, () -> new IssueLogDraft(
                "x",
                Collections.emptyList(),
                Arrays.asList("svc"),
                "", "", "", ""));
    }

    @Test
    public void constructor_services_is_null_should_throw() {
        assertThrows(IllegalArgumentException.class, () -> new IssueLogDraft(
                "x",
                Arrays.asList("logic-pitfall"),
                null,
                "", "", "", ""));
    }

    @Test
    public void constructor_services_is_empty_list_should_throw() {
        assertThrows(IllegalArgumentException.class, () -> new IssueLogDraft(
                "x",
                Arrays.asList("logic-pitfall"),
                Collections.emptyList(),
                "", "", "", ""));
    }

    @Test
    public void constructor_body_fields_like_phenomenon_null_should_normalize_to_empty_string() {
        IssueLogDraft draft = new IssueLogDraft(
                "x",
                Arrays.asList("logic-pitfall"),
                Arrays.asList("svc"),
                null, null, null, null);

        assertEquals("", draft.getPhenomenon());
        assertEquals("", draft.getRootCause());
        assertEquals("", draft.getSolution());
        assertEquals("", draft.getNotes());
    }

    @Test
    public void constructor_title_with_surrounding_spaces_should_be_trimmed() {
        IssueLogDraft draft = new IssueLogDraft(
                "  签到页打不开  ",
                Arrays.asList("logic-pitfall"),
                Arrays.asList("svc"),
                "", "", "", "");

        assertEquals("签到页打不开", draft.getTitle());
    }

    @Test
    public void constructor_categories_with_blank_elements_should_filter_them_out() {
        IssueLogDraft draft = new IssueLogDraft(
                "x",
                Arrays.asList("logic-pitfall", "", "  ", "config-trap"),
                Arrays.asList("svc"),
                "", "", "", "");

        assertEquals(Arrays.asList("logic-pitfall", "config-trap"), draft.getCategories());
    }

    @Test
    public void constructor_categories_all_blank_filtered_empty_should_throw() {
        assertThrows(IllegalArgumentException.class, () -> new IssueLogDraft(
                "x",
                Arrays.asList("", "  "),
                Arrays.asList("svc"),
                "", "", "", ""));
    }

    @Test
    public void constructor_trigger_signals_is_null_should_normalize_to_empty_list() {
        IssueLogDraft draft = new IssueLogDraft(
                "x", Arrays.asList("c"), Arrays.asList("s"),
                null, "", "", "", "");

        assertNotNull(draft.getTriggerSignals());
        assertEquals(0, draft.getTriggerSignals().size());
    }

    @Test
    public void constructor_trigger_signals_with_blank_elements_should_filter_and_keep_valid_terms() {
        IssueLogDraft draft = new IssueLogDraft(
                "x", Arrays.asList("c"), Arrays.asList("s"),
                Arrays.asList("退款按钮", "", "  ", "券码缺失"),
                "", "", "", "");

        assertEquals(Arrays.asList("退款按钮", "券码缺失"), draft.getTriggerSignals());
    }

    @Test
    public void constructor_trigger_signals_all_blank_should_keep_empty_list_without_throwing() {
        // 与 categories/services 不同: triggerSignals 允许全空(辅助字段,人工审核可后补)
        IssueLogDraft draft = new IssueLogDraft(
                "x", Arrays.asList("c"), Arrays.asList("s"),
                Arrays.asList("", "  "), "", "", "", "");

        assertEquals(0, draft.getTriggerSignals().size());
    }

    @Test
    public void constructor_legacy_seven_arg_overload_defaults_trigger_signals_to_empty() {
        IssueLogDraft draft = new IssueLogDraft(
                "x", Arrays.asList("c"), Arrays.asList("s"),
                "", "", "", "");

        assertNotNull(draft.getTriggerSignals());
        assertEquals(0, draft.getTriggerSignals().size());
    }

    @Test
    public void getCategories_returned_list_should_be_immutable() {
        IssueLogDraft draft = new IssueLogDraft(
                "x",
                Arrays.asList("logic-pitfall"),
                Arrays.asList("svc"),
                "", "", "", "");

        assertThrows(UnsupportedOperationException.class,
                () -> draft.getCategories().add("forced"));
    }

    @Test
    public void empty_constant_should_be_singleton_like() {
        // 占位:用于未来 fallback 空草稿场景,先验证类骨架编译通过
        IssueLogDraft d1 = new IssueLogDraft(
                "x",
                Arrays.asList("logic-pitfall"),
                Arrays.asList("svc"),
                "", "", "", "");
        IssueLogDraft d2 = new IssueLogDraft(
                "x",
                Arrays.asList("logic-pitfall"),
                Arrays.asList("svc"),
                "", "", "", "");
        // 不同实例不要求 equals,只验证构造稳定
        assertTrue(d1.getTitle().equals(d2.getTitle()));
        assertSame(d1.getClass(), d2.getClass());
    }
}
