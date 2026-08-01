package com.example.agentweb.app.workbench.run;

import com.example.agentweb.app.chatrun.ChatRunEvent;
import com.example.agentweb.app.chatrun.ChatRunEventStore;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.RunEventCursorExpiredException;
import com.example.agentweb.domain.workbench.RunEventRetentionWindow;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Workbench Run 历史、持久事件分页与实际能力追溯编排。
 *
 * <p>所有查询先经过 Owner 与 exact Run binding 授权；列表/详情通过
 * CQRS 读模型返回 DTO，事件 payload 复用活动 SSE 的相同投影。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Service
@Transactional(readOnly = true)
public class WorkbenchRunHistoryAppService {

    private final WorkbenchRunAccessResolver accessResolver;
    private final WorkbenchRunHistoryQuery historyQuery;
    private final ChatRunEventStore eventStore;

    public WorkbenchRunHistoryAppService(
            WorkbenchRunAccessResolver accessResolver,
            WorkbenchRunHistoryQuery historyQuery,
            ChatRunEventStore eventStore) {
        this.accessResolver = Objects.requireNonNull(
                accessResolver, "accessResolver");
        this.historyQuery = Objects.requireNonNull(
                historyQuery, "historyQuery");
        this.eventStore = Objects.requireNonNull(
                eventStore, "eventStore");
    }

    public WorkbenchRunListPage list(
            OwnerReference actor, WorkbenchId workbenchId,
            WorkbenchRunListRequest request) {
        Objects.requireNonNull(request, "request");
        Workbench workbench = accessResolver.requireOwned(
                actor, workbenchId);
        return historyQuery.list(
                workbenchId,
                workbench.getRepositoryScope().getScopeHash(), request);
    }

    public WorkbenchRunDetailView detail(
            OwnerReference actor, WorkbenchId workbenchId,
            String runId) {
        AuthorizedWorkbenchRun authorized = accessResolver
                .requireAuthorized(actor, workbenchId, runId);
        return historyQuery.findDetail(
                        workbenchId,
                        authorized.getWorkbench().getRepositoryScope()
                                .getScopeHash(), runId)
                .orElseThrow(WorkbenchRunNotFoundException::new);
    }

    public WorkbenchRunEventPage events(
            OwnerReference actor, WorkbenchId workbenchId,
            String runId, WorkbenchRunEventPageRequest request) {
        Objects.requireNonNull(request, "request");
        AuthorizedWorkbenchRun authorized = accessResolver
                .requireAuthorized(actor, workbenchId, runId);
        ChatRun run = authorized.getRun();
        RunEventRetentionWindow retention = RunEventRetentionWindow.from(
                run.getLastEventSeq(),
                eventStore.findEarliestSequence(run.getId()));
        requireReplayCursor(runId, request.getAfter(), retention);
        long lastEventSequence = retention.getLastEventSequence();
        List<ChatRunEvent> persisted = eventStore.findAfterThrough(
                run.getId(), request.getAfter(), lastEventSequence,
                request.getLimit());
        List<WorkbenchRunEvent> projected = project(
                authorized, persisted);
        long deliveredThrough = projected.isEmpty()
                ? request.getAfter()
                : projected.get(projected.size() - 1).getSequence();
        return new WorkbenchRunEventPage(
                runId, request.getAfter(), deliveredThrough,
                lastEventSequence,
                retention.getEarliestRetainedSequence(),
                retention.hasMoreAfter(deliveredThrough), projected);
    }

    public WorkbenchRunCapabilityView capability(
            OwnerReference actor, WorkbenchId workbenchId,
            String runId) {
        AuthorizedWorkbenchRun authorized = accessResolver
                .requireAuthorized(actor, workbenchId, runId);
        return WorkbenchRunCapabilityView.from(
                authorized.getSnapshot());
    }

    private void requireReplayCursor(
            String runId, long cursor,
            RunEventRetentionWindow retention) {
        try {
            retention.requireReplayAfter(cursor);
        } catch (RunEventCursorExpiredException failure) {
            throw new WorkbenchRunCursorExpiredException(
                    runId,
                    failure.getEarliestRetainedSequence(),
                    failure.getLastEventSequence(),
                    "workbench run event cursor expired");
        }
    }

    private List<WorkbenchRunEvent> project(
            AuthorizedWorkbenchRun authorized,
            List<ChatRunEvent> persisted) {
        List<WorkbenchRunEvent> projected =
                new ArrayList<WorkbenchRunEvent>(persisted.size());
        for (ChatRunEvent event : persisted) {
            projected.add(new WorkbenchRunEvent(
                    event.getSeq(), event.getEventType(),
                    WorkbenchRunEventPayloadFactory.project(
                            authorized.getSnapshot(), event)));
        }
        return projected;
    }
}
