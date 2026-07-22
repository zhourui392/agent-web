package com.example.agentweb.domain.chat;

import java.util.regex.Pattern;

/**
 * 聊天消息中的图片路径引用规则。
 *
 * <p>仅把独占一行、且扩展名为受支持图片类型的完整路径视为引用。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public final class MessageImageReferences {

    private static final Pattern IMAGE_PATH = Pattern.compile(
            "^.+[/\\\\][^/\\\\]+\\.(png|jpe?g|gif|webp|bmp)$", Pattern.CASE_INSENSITIVE);

    private MessageImageReferences() {
    }

    public static boolean contains(String messageContent, String requestedPath) {
        if (messageContent == null || requestedPath == null || !IMAGE_PATH.matcher(requestedPath).matches()) {
            return false;
        }
        String[] lines = messageContent.split("\\R");
        for (String line : lines) {
            if (requestedPath.equals(line.trim())) {
                return true;
            }
        }
        return false;
    }
}
