package com.example.agentweb.app;

import com.example.agentweb.app.agentrun.port.AgentGateway;
import com.example.agentweb.app.agentrun.AgentRunContext;
import com.example.agentweb.app.agentrun.PromptAssemblyResult;
import com.example.agentweb.app.agentrun.PromptAssemblyService;
import com.example.agentweb.app.agentrun.PromptPart;
import com.example.agentweb.app.agentrun.RunRecallPolicyFactory;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.auth.UserContext;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionCache;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.schedule.ScheduledTask;
import com.example.agentweb.domain.schedule.ScheduledTaskRepository;
import com.example.agentweb.infra.AgentRunProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 定时任务应用服务编排单测：验证「创建写归属」与「执行透传归属到对话会话」两条业务结果。
 * 真实 {@link CurrentUserProvider}(喂 StubUserContext) + Mock Repository/Gateway 边界。
 *
 * @author zhourui(V33215020)
 */
public class ScheduledTaskServiceImplTest {

    private String currentUserId;

    private final UserContext userContext = () ->
            currentUserId == null ? Optional.empty()
                    : Optional.of(new LoginUser(currentUserId, currentUserId, null));

    private ScheduledTaskServiceImpl newService(ScheduledTaskRepository taskRepo,
                                                SessionRepository sessionRepo,
                                                SessionCache sessionCache,
                                                AgentGateway gateway) {
        PromptAssemblyService promptAssemblyService = mock(PromptAssemblyService.class);
        stubPromptAssemblyPassThrough(promptAssemblyService);
        return newService(taskRepo, sessionRepo, sessionCache, gateway, promptAssemblyService);
    }

    private ScheduledTaskServiceImpl newService(ScheduledTaskRepository taskRepo,
                                                SessionRepository sessionRepo,
                                                SessionCache sessionCache,
                                                AgentGateway gateway,
                                                PromptAssemblyService promptAssemblyService) {
        return new ScheduledTaskServiceImpl(taskRepo, sessionRepo, sessionCache, gateway,
                new CurrentUserProvider(userContext), promptAssemblyService,
                new RunRecallPolicyFactory(new AgentRunProperties()));
    }

    @Test
    public void create_should_stamp_current_user_as_owner() {
        ScheduledTaskRepository taskRepo = mock(ScheduledTaskRepository.class);
        ScheduledTaskServiceImpl service = newService(taskRepo,
                mock(SessionRepository.class), mock(SessionCache.class), mock(AgentGateway.class));
        currentUserId = "V33215020";

        service.create("nightly", "0 0 1 * * *", "跑昨日报告", "/tmp/wd");

        ArgumentCaptor<ScheduledTask> captor = ArgumentCaptor.forClass(ScheduledTask.class);
        verify(taskRepo).save(captor.capture());
        assertEquals("V33215020", captor.getValue().getUserId(), "创建时应把当前用户写为任务归属");
    }

    @Test
    public void doExecute_should_attribute_session_to_task_owner() {
        ScheduledTaskRepository taskRepo = mock(ScheduledTaskRepository.class);
        SessionRepository sessionRepo = mock(SessionRepository.class);
        AgentGateway gateway = mock(AgentGateway.class);
        ScheduledTaskServiceImpl service = newService(taskRepo, sessionRepo, mock(SessionCache.class), gateway);

        ScheduledTask task = new ScheduledTask("owned-task", "0 0 1 * * *", "p", "/tmp/wd");
        task.setUserId("alice");
        when(taskRepo.findById("tid")).thenReturn(task);
        // 后台执行无登录上下文
        currentUserId = null;

        service.executeTask("tid");

        ArgumentCaptor<ChatSession> captor = ArgumentCaptor.forClass(ChatSession.class);
        verify(sessionRepo).saveSession(captor.capture());
        assertEquals(com.example.agentweb.domain.shared.AgentType.CODEX, captor.getValue().getAgentType(),
                "定时任务执行默认应走 Codex, 避免继续拉起 Claude CLI");
        assertEquals("alice", captor.getValue().getUserId(),
                "执行产生的对话会话应归属到任务创建者, 而非匿名(null)");
        // 任务结束回写 last_run 仍按任务 id
        verify(taskRepo).updateLastRun(eq(task.getId()), any(), any());
    }

    @Test
    public void doExecute_should_send_assembled_prompt_to_cli_and_keep_user_message_original() throws Exception {
        ScheduledTaskRepository taskRepo = mock(ScheduledTaskRepository.class);
        SessionRepository sessionRepo = mock(SessionRepository.class);
        AgentGateway gateway = mock(AgentGateway.class);
        PromptAssemblyService promptAssemblyService = mock(PromptAssemblyService.class);
        when(promptAssemblyService.assemble(any(AgentRunContext.class))).thenAnswer(inv -> {
            AgentRunContext context = inv.getArgument(0);
            return promptResult("[Workspace Context]\n" + context.getOriginalInput());
        });
        ScheduledTaskServiceImpl service = newService(taskRepo, sessionRepo,
                mock(SessionCache.class), gateway, promptAssemblyService);
        ScheduledTask task = new ScheduledTask("owned-task", "0 0 1 * * *", "p", "/tmp/wd");
        when(taskRepo.findById("tid")).thenReturn(task);

        service.executeTask("tid");

        verify(sessionRepo).addMessage(any(), org.mockito.ArgumentMatchers.argThat(
                msg -> "user".equals(msg.getRole()) && "p".equals(msg.getContent())));
        verify(gateway).runStream(eq(com.example.agentweb.domain.shared.AgentType.CODEX),
                eq("/tmp/wd"), eq("[Workspace Context]\np"), any(), eq(null), eq(null),
                anyLong(), any(), any());
    }

    private void stubPromptAssemblyPassThrough(PromptAssemblyService service) {
        when(service.assemble(any(AgentRunContext.class))).thenAnswer(inv -> {
            AgentRunContext context = inv.getArgument(0);
            return promptResult(context.getOriginalInput());
        });
    }

    private PromptAssemblyResult promptResult(String prompt) {
        return new PromptAssemblyResult(
                prompt,
                "hash",
                java.util.Collections.<PromptPart>emptyList(),
                null,
                null,
                java.util.Collections.emptyList(),
                "none");
    }
}
