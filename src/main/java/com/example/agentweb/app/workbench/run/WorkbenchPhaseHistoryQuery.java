package com.example.agentweb.app.workbench.run;

import com.example.agentweb.domain.workbench.PhaseConversationProvisioning;
import com.example.agentweb.domain.workbench.WorkbenchPhaseHistory;

/**
 * 当前 Workbench Phase Session 的有界历史读端口。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface WorkbenchPhaseHistoryQuery {

    WorkbenchPhaseHistory load(PhaseConversationProvisioning provisioning);
}
