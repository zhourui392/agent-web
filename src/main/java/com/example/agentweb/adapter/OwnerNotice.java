package com.example.agentweb.adapter;

import lombok.Value;

/**
 * 需求属主通知载荷。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Value
public class OwnerNotice {

    String requirementId;

    String title;

    String body;
}
