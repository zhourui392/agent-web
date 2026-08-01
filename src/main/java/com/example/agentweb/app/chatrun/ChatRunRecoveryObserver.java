package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunRecoveryDecision;

/**
 * 公共 ChatRun 恢复结果观察扩展点；不得改变恢复决策或触发运行副作用。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface ChatRunRecoveryObserver {

    void reconciled(ChatRun run, ChatRunRecoveryDecision decision);

    void failed(ChatRun run);
}
