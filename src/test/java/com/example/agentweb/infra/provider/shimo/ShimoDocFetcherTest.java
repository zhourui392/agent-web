package com.example.agentweb.infra.provider.shimo;

import com.example.agentweb.adapter.AgentGateway;
import com.example.agentweb.adapter.requirement.FetchedDoc;
import com.example.agentweb.domain.shared.AgentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ShimoDocFetcher 单测(Mockito mock AgentGateway):supports 主域/子域/异构 host 判定、
 * 标题提取、异常包装。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class ShimoDocFetcherTest {

    private static final String DOC_URL = "https://shimo.im/docs/abc123";
    private static final String WORKING_DIR = "D:/work/shimo";

    private AgentGateway agentGateway;
    private ShimoDocFetcher fetcher;

    @BeforeEach
    public void setUp() {
        agentGateway = Mockito.mock(AgentGateway.class);
        fetcher = new ShimoDocFetcher(agentGateway, AgentType.CLAUDE, WORKING_DIR);
    }

    @Test
    public void supports_shimo_main_host_should_return_true() {
        // When & Then
        assertTrue(fetcher.supports("https://shimo.im/docs/abc123"));
    }

    @Test
    public void supports_shimo_sub_domain_should_return_true() {
        // When & Then
        assertTrue(fetcher.supports("https://xxx.shimo.im/docs/abc123"));
    }

    @Test
    public void supports_other_host_should_return_false() {
        // When & Then: 别的 host 以及仅后缀相似的 host 都不认
        assertFalse(fetcher.supports("https://example.com/docs/abc123"));
        assertFalse(fetcher.supports("https://notshimo.im/docs/abc123"));
    }

    @Test
    public void supports_malformed_url_should_return_false() {
        // When & Then
        assertFalse(fetcher.supports("::::not-a-url"));
    }

    @Test
    public void supports_null_or_blank_should_return_false() {
        // When & Then
        assertFalse(fetcher.supports(null));
        assertFalse(fetcher.supports("   "));
    }

    @Test
    public void fetch_with_title_line_should_extract_title() throws Exception {
        // Given: agent 输出首行是 "# 标题"
        String output = "# 商详页优化需求\n\n## 背景\n正文内容";
        Mockito.when(agentGateway.runOnce(Mockito.eq(AgentType.CLAUDE), Mockito.eq(WORKING_DIR),
                Mockito.anyString(), Mockito.isNull())).thenReturn(output);

        // When
        FetchedDoc doc = fetcher.fetch(DOC_URL);

        // Then: 标题提取正确,全文作 markdown,指令里带文档 URL
        assertEquals("商详页优化需求", doc.getTitle());
        assertEquals(output, doc.getMarkdown());
        assertNotNull(doc.getFetchedAt());
        ArgumentCaptor<String> instructionCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(agentGateway).runOnce(Mockito.eq(AgentType.CLAUDE), Mockito.eq(WORKING_DIR),
                instructionCaptor.capture(), Mockito.isNull());
        assertTrue(instructionCaptor.getValue().contains(DOC_URL));
    }

    @Test
    public void fetch_without_title_line_should_use_default_title() throws Exception {
        // Given: 输出没有 "# " 开头的标题行
        Mockito.when(agentGateway.runOnce(Mockito.eq(AgentType.CLAUDE), Mockito.eq(WORKING_DIR),
                Mockito.anyString(), Mockito.isNull())).thenReturn("没有标题的正文");

        // When
        FetchedDoc doc = fetcher.fetch(DOC_URL);

        // Then
        assertEquals("未命名文档", doc.getTitle());
        assertEquals("没有标题的正文", doc.getMarkdown());
    }

    @Test
    public void fetch_when_agent_throws_io_exception_should_wrap_as_illegal_state() throws Exception {
        // Given: agent 执行失败
        Mockito.when(agentGateway.runOnce(Mockito.eq(AgentType.CLAUDE), Mockito.eq(WORKING_DIR),
                Mockito.anyString(), Mockito.isNull())).thenThrow(new IOException("cli boom"));

        // When & Then
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> fetcher.fetch(DOC_URL));
        assertTrue(thrown.getMessage().contains(DOC_URL));
    }
}
