package com.example.agentweb.interfaces;

import com.example.agentweb.app.chatrun.ToolInvocationStatisticsQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @author alex
 */
@RestController
@RequestMapping("/api/admin-tool-invocation-statistics")
public class AdminToolInvocationStatisticsController {
    private final ToolInvocationStatisticsQueryService service;

    public AdminToolInvocationStatisticsController(ToolInvocationStatisticsQueryService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ToolInvocationStatisticsQueryService.Overview overview(Query query) {
        return service.overview(query.filter());
    }

    @GetMapping("/daily-trend")
    public List<ToolInvocationStatisticsQueryService.DailyPoint> dailyTrend(Query query) {
        query.validateTime();
        return service.dailyTrend(query.filter());
    }

    @GetMapping("/rankings")
    public ToolInvocationStatisticsQueryService.Page<ToolInvocationStatisticsQueryService.RankingRow> rankings(
            Query query,
            @RequestParam(defaultValue = "TOOL") ToolInvocationStatisticsQueryService.RankingType type,
            @RequestParam(defaultValue = "INVOCATION_COUNT_DESC") ToolInvocationStatisticsQueryService.RankingOrder order,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        query.validateTime();
        return service.rankings(query.filter(), type, order, Math.max(1, page), boundedSize(size));
    }

    @GetMapping("/conversations")
    public ToolInvocationStatisticsQueryService.Page<ToolInvocationStatisticsQueryService.ConversationRow> conversations(
            Query query,
            @RequestParam(defaultValue = "INVOCATION_COUNT_DESC") ToolInvocationStatisticsQueryService.ConversationOrder order,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        query.validateTime();
        return service.conversations(query.filter(), order, Math.max(1, page), boundedSize(size));
    }

    private int boundedSize(int size) { return Math.min(100, Math.max(1, size)); }

    public static final class Query {
        public Long startedAfter, startedBefore;
        public String provider, invocationKind, status, source, triggerSource, analysisName, sessionId, runId;
        public void setStartedAfter(Long value){startedAfter=value;} public void setStartedBefore(Long value){startedBefore=value;}
        public void setProvider(String value){provider=value;} public void setInvocationKind(String value){invocationKind=value;}
        public void setStatus(String value){status=value;} public void setSource(String value){source=value;}
        public void setTriggerSource(String value){triggerSource=value;} public void setAnalysisName(String value){analysisName=value;}
        public void setSessionId(String value){sessionId=value;} public void setRunId(String value){runId=value;}
        void validateTime(){ if(startedAfter!=null&&startedBefore!=null&&startedAfter>=startedBefore) throw new IllegalArgumentException("startedAfter must be earlier than startedBefore"); }
        ToolInvocationStatisticsQueryService.Filter filter(){ validateTime(); return ToolInvocationStatisticsQueryService.Filter.builder()
                .startedAfter(startedAfter).startedBefore(startedBefore).provider(provider).invocationKind(invocationKind)
                .status(status).source(source).triggerSource(triggerSource).analysisName(analysisName).sessionId(sessionId).runId(runId).build(); }
    }
}
