package com.example.agentweb.adapter;

/**
 * 平台通知端口:需求属主通知。飞书摘除后由 no-op 实现降级为只打日志。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public interface NotificationGateway {

    /** 通知需求属主 */
    void notifyOwner(String userId, OwnerNotice notice);
}
