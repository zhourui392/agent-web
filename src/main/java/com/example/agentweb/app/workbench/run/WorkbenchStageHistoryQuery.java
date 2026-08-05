package com.example.agentweb.app.workbench.run;

import com.example.agentweb.domain.workbench.WorkbenchStageConversationHistory;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageConversationProvisioning;

/**
 * 当前 Dynamic Stage Session 的有界历史读端口。
 *
 * @author alex
 * @since 2026-08-05
 */
public interface WorkbenchStageHistoryQuery {

    WorkbenchStageConversationHistory load(
            WorkbenchStageConversationProvisioning provisioning);
}
