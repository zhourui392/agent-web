package com.example.agentweb.app.workbench.query;

import java.util.Optional;

/**
 * Workbench Owner 侧列表与详情的 CQRS 读端口。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface WorkbenchQueryService {

    WorkbenchListPage listByOwner(String ownerId, WorkbenchListRequest request);

    Optional<WorkbenchDetailView> findDetailByOwner(String ownerId, String workbenchId);

    default Optional<WorkbenchStageConversationMessagePage>
            findCurrentStageConversationByOwner(
                    String ownerId, String workbenchId,
                    String stageInstanceIdentifier) {
        return findCurrentStageConversationByOwner(
                ownerId, workbenchId, stageInstanceIdentifier,
                WorkbenchStageConversationMessageRequest.latest());
    }

    Optional<WorkbenchStageConversationMessagePage>
            findCurrentStageConversationByOwner(
                    String ownerId, String workbenchId,
                    String stageInstanceIdentifier,
                    WorkbenchStageConversationMessageRequest request);
}
