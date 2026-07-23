package com.example.agentweb.infra.setting;

import java.util.Optional;

/**
 * 运行时可变配置(app_setting)读写口。
 * <p>通用 key-value,语义由具体配置仓储解析。
 * 抽接口是为了让运行设置可脱离 DB 单测。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-25
 */
public interface AppSettingRepository {

    /**
     * 读取一个配置值。
     *
     * @param key 配置键
     * @return 存在则为对应值,否则 {@link Optional#empty()}
     */
    Optional<String> get(String key);

    /**
     * 写入(存在则覆盖)一个配置值。
     *
     * @param key             配置键
     * @param value           配置值
     * @param updatedAtMillis 更新时间(epoch millis)
     */
    void put(String key, String value, long updatedAtMillis);

    /**
     * 删除一个配置值。
     *
     * @param key 配置键
     */
    void delete(String key);
}
