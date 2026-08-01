package com.example.agentweb.app.workbench.query;

import com.example.agentweb.domain.workbench.WorkbenchPhase;

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

    default Optional<PhaseConversationMessagePage>
            findCurrentPhaseConversationByOwner(
                    String ownerId, String workbenchId,
                    WorkbenchPhase phase) {
        return findCurrentPhaseConversationByOwner(
                ownerId, workbenchId, phase,
                PhaseConversationMessageRequest.latest());
    }

    Optional<PhaseConversationMessagePage> findCurrentPhaseConversationByOwner(
            String ownerId, String workbenchId, WorkbenchPhase phase,
            PhaseConversationMessageRequest request);
}
