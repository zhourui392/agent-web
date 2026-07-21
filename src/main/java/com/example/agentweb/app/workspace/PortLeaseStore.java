package com.example.agentweb.app.workspace;

import java.util.List;

/**
 * 端口租约存取（纯 infra 资源，聚合不感知；接口放 app 供编排消费，实现在 infra，
 * 对齐 QueryService 接口放置模式以守 ArchUnit A4）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public interface PortLeaseStore {

    /**
     * 从全局池分配首个空闲端口：INSERT 唯一键冲突即重试下一个（纯持久化竞态处理）。
     *
     * @return 分配到的端口
     * @throws IllegalStateException 池耗尽时
     */
    int allocate(String workspaceId);

    /** 随工作区释放整体删除该工作区的全部租约。 */
    void releaseAll(String workspaceId);

    List<Integer> portsOf(String workspaceId);
}
