package com.example.agentweb.adapter.delivery;

import lombok.Value;

/**
 * webhook 原始信封:controller 完成鉴权后交给 parseWebhook 防腐解析。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Value
public class WebhookEnvelope {

    /** X-Gitlab-Event 头(如 "Pipeline Hook") */
    String eventType;

    /** 原始 JSON body */
    String rawBody;
}
