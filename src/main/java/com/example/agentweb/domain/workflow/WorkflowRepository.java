package com.example.agentweb.domain.workflow;

import java.util.List;

/**
 * 工作流定义持久化端口。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-12
 */
public interface WorkflowRepository {

    /**
     * 保存工作流定义。
     *
     * @param workflow 工作流定义
     */
    void save(Workflow workflow);

    /**
     * 更新工作流定义。
     *
     * @param workflow 工作流定义
     */
    void update(Workflow workflow);

    /**
     * 按 ID 查询工作流。
     *
     * @param id 工作流 ID
     * @return 工作流定义,不存在返回 null
     */
    Workflow findById(String id);

    /**
     * 查询全部工作流。
     *
     * @return 工作流列表
     */
    List<Workflow> findAll();

    /**
     * 删除工作流定义。
     *
     * @param id 工作流 ID
     */
    void deleteById(String id);
}
