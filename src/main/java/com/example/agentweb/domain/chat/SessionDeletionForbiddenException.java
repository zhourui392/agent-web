package com.example.agentweb.domain.chat;

/**
 * 删除非自己创建的会话时抛出。映射为 HTTP 403。
 *
 * <p>独立于可见性隔离开关：会话可全员可见，但删除始终按创建者归属收紧。
 * 无归属的老数据/公共会话(userId 为 null)不受此限制。</p>
 *
 * @author zhourui(V33215020)
 */
public class SessionDeletionForbiddenException extends RuntimeException {

    public SessionDeletionForbiddenException(String sessionId, String currentUserId) {
        super("session " + sessionId + " can only be deleted by its creator (current user: "
                + (currentUserId == null ? "anonymous" : currentUserId) + ")");
    }
}
