package com.example.agentweb.domain.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MessageImageReferences} 消息图片引用规则测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class MessageImageReferencesTest {

    @Test
    void contains_should_RequireExactStandaloneImagePath() {
        String content = "question\n/work/upload_pic/s1/a.png\nmore";

        assertTrue(MessageImageReferences.contains(content, "/work/upload_pic/s1/a.png"));
        assertFalse(MessageImageReferences.contains(content, "/work/upload_pic/s1/a.png.bak"));
        assertFalse(MessageImageReferences.contains(content, "/work/upload_pic/s1"));
    }

    @Test
    void contains_should_RejectNonImageAndEmbeddedText() {
        assertFalse(MessageImageReferences.contains("/work/secret.txt", "/work/secret.txt"));
        assertFalse(MessageImageReferences.contains("see /work/a.png now", "/work/a.png"));
        assertTrue(MessageImageReferences.contains("C:\\work\\a.JPEG", "C:\\work\\a.JPEG"));
    }
}
