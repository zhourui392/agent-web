package com.example.agentweb.domain.requirement;

/**
 * issue 接入无法确定需求属主（作者与回落接待人皆缺失），拒收该 issue。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class OwnerUnresolvedException extends RuntimeException {

    public OwnerUnresolvedException() {
        super("无法确定需求 owner: issue 作者为空且未配置回落接待人");
    }
}
