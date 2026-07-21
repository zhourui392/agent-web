package com.example.agentweb.domain.git;

import java.util.Optional;

/**
 * {@link UserGitConfig} 聚合的写侧仓储（生命周期），签名只允许 domain 类型，infra 实现。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
public interface UserGitConfigRepository {

    /**
     * 按用户工号查配置。
     *
     * @param userId 用户工号
     * @return 聚合，不存在时 {@link Optional#empty()}
     */
    Optional<UserGitConfig> findByUserId(String userId);

    /**
     * 保存（新增或覆盖）配置。
     *
     * @param config 聚合
     */
    void save(UserGitConfig config);

    /**
     * 删除某用户配置（回落机器默认身份）。
     *
     * @param userId 用户工号
     */
    void deleteByUserId(String userId);
}
