package com.example.agentweb.app.chatrun;

import java.util.List;

/**
 * Application use cases for resumable chat runs.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public interface ChatRunAppService {

    ChatRunSubmission submit(SubmitChatRunCommand command);

    ChatRunView find(String runId);

    List<ActiveChatRunView> findActive();

    ChatRunView stop(String runId);
}
