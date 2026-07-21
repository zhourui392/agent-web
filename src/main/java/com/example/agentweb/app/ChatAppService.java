package com.example.agentweb.app;

import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.Feedback;
import com.example.agentweb.domain.slashcommand.SlashCommand;
import com.example.agentweb.interfaces.dto.SendMessageRequest;
import com.example.agentweb.interfaces.dto.StartSessionRequest;
import com.example.agentweb.interfaces.dto.TruncateResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

/**
 * Application service orchestrating session lifecycle and message handling.
 * @author zhourui(V33215020)
 */
public interface ChatAppService {

    /**
     * 创建新会话并初始化 agent 类型/工作目录/env 锁定状态.
     * @param req 创建会话请求
     * @param clientIp 发起会话的客户端来源 IP(由接口层从 HTTP 请求解析), 空白表示未采集
     * @return 新建的会话聚合根
     */
    ChatSession startSession(StartSessionRequest req, String clientIp);

    /**
     * Send one message to an agent and get consolidated output.
     * Note: Interactive PTY is not supported in v0; the CLI must be invokable non-interactively.
     * @param sessionId 会话 ID
     * @param req 发送请求
     * @return CLI 合并输出
     * @throws IOException 进程启动失败或读 IO 异常
     * @throws InterruptedException 等待进程退出时被中断
     */
    String sendMessage(String sessionId, SendMessageRequest req) throws IOException, InterruptedException;

    /**
     * 按 sessionId 查询会话.
     * @param sessionId 会话 ID
     * @return 会话聚合根,不存在则返回 null
     */
    ChatSession getSession(String sessionId);

    /**
     * Stream output using Server-Sent Events.
     * @param sessionId 会话 ID
     * @param message 用户输入文本
     * @param resumeId Optional resume ID for continuing a conversation
     * @param env 环境约束 key,空表示无约束
     * @param recallEnabled 前端"RAG 召回"开关; true 时整条消息自动召回历史参考拼到送 CLI 文本前 (chat-rag 未启用则无效)
     * @return 注册到响应的 SseEmitter,生命周期由调用方维持
     */
    SseEmitter streamMessage(String sessionId, String message, String resumeId, String env, boolean recallEnabled);

    /**
     * Stop a running stream for the given session.
     * @param sessionId 会话 ID
     */
    void stopSession(String sessionId);

    /**
     * Check if the agent process is still running for the given session.
     * @param sessionId 会话 ID
     * @return true 表示该会话有正在运行的 CLI 进程
     */
    boolean isSessionRunning(String sessionId);

    /**
     * 列出指定会话工作目录可用的 slash 命令.
     * @param sessionId 会话 ID
     * @return 可用命令列表,无可用命令时返回空列表
     */
    List<SlashCommand> listCommands(String sessionId);

    /**
     * 删除会话并清理关联资源(内存缓存、持久化记录、{@code upload_pic/<sessionId>/} 图片目录)。
     * 图片清理失败不阻断会话删除主流程。
     * @param sessionId 会话 ID
     */
    void deleteSession(String sessionId);

    /**
     * Truncate all messages with id >= fromId in the given session, clear resume_id.
     * Returns deleted count, the prefill content (original user text if fromId points to a user
     * message, empty otherwise), and whether resume_id was cleared.
     */
    TruncateResult truncateFrom(String sessionId, long fromId);

    /**
     * 保存(整体替换)会话的反馈评价——用户对该对话 AI 分析正确性的评价。
     * <p>rating 为空/空白表示未评分;comment 纯空白归一化为 null。</p>
     * @param sessionId 会话 ID
     * @param rating 评分枚举名(CORRECT/PARTIALLY_CORRECT/INCORRECT),null/空白表示未评分
     * @param comment 文字补充,可空
     * @return 已保存的反馈值对象
     */
    Feedback saveFeedback(String sessionId, String rating, String comment);

    /**
     * 查询会话的反馈评价.
     * @param sessionId 会话 ID
     * @return 反馈值对象,从未评价过则返回 null
     */
    Feedback getFeedback(String sessionId);

    /**
     * 为会话生成（或返回既有的）分享 token。token 生成规则由领域
     * {@link com.example.agentweb.domain.chat.ShareToken} 持有。
     *
     * @param sessionId 会话 ID
     * @return 已落库的分享 token
     * @throws IllegalArgumentException 会话不存在或当前用户不可见
     */
    String shareSession(String sessionId);

    /**
     * 以分享 token 续聊：解析 token → 会话，复用 owner 侧流式管线
     * （resumeId / env 一律以会话持久态为准）。
     *
     * @param shareToken 分享 token
     * @param message 用户输入文本
     * @param recallEnabled RAG 召回开关
     * @return 注册到响应的 SseEmitter
     * @throws IllegalArgumentException token 无效
     */
    SseEmitter streamSharedMessage(String shareToken, String message, boolean recallEnabled);
}
