package com.example.agentweb.app.mode;

import com.example.agentweb.config.ModeSwitchProperties;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.ChatSessionNotFoundException;
import com.example.agentweb.domain.chat.SessionCache;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.chatrun.ChatRunActivityGuard;
import com.example.agentweb.domain.mode.ChatMode;
import com.example.agentweb.domain.mode.ChatModeRepository;
import com.example.agentweb.domain.mode.DuplicateHandoffException;
import com.example.agentweb.domain.mode.HandoffDocument;
import com.example.agentweb.domain.mode.HandoffDocumentRepository;
import com.example.agentweb.domain.mode.ModeSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 模式切换用例实现：导出旧会话转录 + 创建绑定新模式的新会话，单步同步。
 *
 * <p>事务边界：交接文件（以预生成 handoff id 命名）先于数据库事务写入；
 * handoff 落库 + 新会话落库同一事务，失败补偿删除已写文件。
 * 重复提交以 (from_session_id, idempotency_key) 唯一索引兜底，命中即回放既有结果。</p>
 *
 * @author alex
 * @since 2026-08-20
 */
@Service
@Slf4j
public class ModeSwitchAppServiceImpl implements ModeSwitchAppService {

    private final SessionRepository sessionRepository;
    private final SessionCache sessionCache;
    private final ChatModeRepository modeRepository;
    private final HandoffDocumentRepository handoffRepository;
    private final ChatRunActivityGuard chatRunActivityGuard;
    private final HandoffTranscriptQueryService transcriptQueryService;
    private final HandoffFilePort handoffFilePort;
    private final CurrentUserProvider currentUserProvider;
    private final ModeSwitchProperties properties;
    private final TransactionTemplate transactionTemplate;

    public ModeSwitchAppServiceImpl(SessionRepository sessionRepository,
                                    SessionCache sessionCache,
                                    ChatModeRepository modeRepository,
                                    HandoffDocumentRepository handoffRepository,
                                    ChatRunActivityGuard chatRunActivityGuard,
                                    HandoffTranscriptQueryService transcriptQueryService,
                                    HandoffFilePort handoffFilePort,
                                    CurrentUserProvider currentUserProvider,
                                    ModeSwitchProperties properties,
                                    TransactionTemplate transactionTemplate) {
        this.sessionRepository = sessionRepository;
        this.sessionCache = sessionCache;
        this.modeRepository = modeRepository;
        this.handoffRepository = handoffRepository;
        this.chatRunActivityGuard = chatRunActivityGuard;
        this.transcriptQueryService = transcriptQueryService;
        this.handoffFilePort = handoffFilePort;
        this.currentUserProvider = currentUserProvider;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public ModeSwitchResult switchMode(String sessionId, String targetModeId, String idempotencyKey) {
        ChatSession source = loadOwnedSource(sessionId);
        chatRunActivityGuard.requireInactive(sessionId);

        Optional<HandoffDocument> replay = handoffRepository
                .findByFromSessionAndIdempotencyKey(sessionId, idempotencyKey);
        if (replay.isPresent()) {
            log.info("mode-switch-idempotent-replay sessionId={} newSessionId={}",
                    sessionId, replay.get().getToSessionId());
            return new ModeSwitchResult(replay.get().getToSessionId(), replay.get().getId());
        }

        ChatMode targetMode = null;
        if (targetModeId != null && !targetModeId.trim().isEmpty()) {
            targetMode = modeRepository.find(targetModeId)
                    .orElseThrow(() -> new com.example.agentweb.domain.mode.ModeNotFoundException(targetModeId));
            targetMode.requireOwnedBy(currentUserProvider.currentUserId());
        }
        final ChatMode resolvedTargetMode = targetMode;
        final ModeSnapshot targetSnapshot = resolvedTargetMode == null
                ? null : resolvedTargetMode.snapshot();

        String handoffId = UUID.randomUUID().toString();
        String newSessionId = UUID.randomUUID().toString();
        HandoffTranscriptFacts facts = transcriptQueryService.loadTranscript(
                sessionId, properties.getTranscriptTail());
        String content = renderTranscript(source, resolvedTargetMode, facts);
        String relativePath = handoffFilePort.export(source.getWorkingDir(), handoffId, content);

        try {
            transactionTemplate.executeWithoutResult(tx -> {
                ChatSession target = ChatSession.createSwitched(
                        source, newSessionId, targetSnapshot, handoffId, Instant.now());
                sessionRepository.saveSession(target);
                sessionCache.save(target);
                sessionRepository.addMessage(newSessionId,
                        new com.example.agentweb.domain.chat.ChatMessage(
                                "system", switchNote(source, resolvedTargetMode, handoffId)));
                handoffRepository.save(new HandoffDocument(
                        handoffId, source.getId(), newSessionId,
                        source.getModeId(),
                        resolvedTargetMode == null ? null : resolvedTargetMode.getId(),
                        relativePath, idempotencyKey, Instant.now()));
            });
        } catch (DuplicateHandoffException raced) {
            // 并发同幂等键：唯一索引兜底，回读既有结果
            HandoffDocument existing = handoffRepository
                    .findByFromSessionAndIdempotencyKey(sessionId, idempotencyKey)
                    .orElseThrow(() -> raced);
            log.info("mode-switch-race-replayed sessionId={} newSessionId={}",
                    sessionId, existing.getToSessionId());
            return new ModeSwitchResult(existing.getToSessionId(), existing.getId());
        } catch (RuntimeException failure) {
            handoffFilePort.deleteQuietly(source.getWorkingDir(), relativePath);
            throw failure;
        }
        log.info("mode-switch-completed fromSession={} toSession={} handoffId={} fromMode={} toMode={}",
                sessionId, newSessionId, handoffId, source.getModeId(),
                resolvedTargetMode == null ? null : resolvedTargetMode.getId());
        return new ModeSwitchResult(newSessionId, handoffId);
    }

    /** 切换痕迹以系统消息落进新会话对话流，刷新/恢复后仍可见。 */
    private String switchNote(ChatSession source, ChatMode targetMode, String handoffId) {
        String sourceLabel = source.getModeSnapshot() == null
                ? "默认模式" : source.getModeSnapshot().getDisplayName();
        String targetLabel = targetMode == null
                ? "默认模式" : targetMode.getDisplayName();
        return "[模式切换] 已从会话 " + shortId(source.getId())
                + "（模式：" + sourceLabel + "）切换到「" + targetLabel
                + "」，交接文档 " + handoffId + " 已注入本轮上下文。";
    }

    /** 会话 id 仅取前 8 位作展示；不用 abbreviate（其截断后缀面向多行文本）。 */
    private static String shortId(String sessionId) {
        return sessionId.length() <= 8 ? sessionId : sessionId.substring(0, 8);
    }

    private ChatSession loadOwnedSource(String sessionId) {
        ChatSession source = sessionRepository.findById(sessionId);
        if (source == null) {
            throw new ChatSessionNotFoundException(sessionId);
        }
        source.requireOrdinaryChat();
        // 授权：仅属主可切换；无归属老数据沿用删除权语义（任意登录用户可操作）
        source.requireDeletableBy(currentUserProvider.currentUserId());
        return source;
    }

    /** 服务端模板化转录：首轮目标 + 尾部消息 + 文件清单，零 LLM 调用。 */
    private String renderTranscript(ChatSession source, ChatMode targetMode,
                                    HandoffTranscriptFacts facts) {
        StringBuilder md = new StringBuilder();
        md.append("# 会话交接（Handoff）\n\n");
        md.append("- 导出时间: ").append(Instant.now()).append('\n');
        md.append("- 源会话: `").append(source.getId()).append("`\n");
        md.append("- 源模式: ")
                .append(source.getModeSnapshot() == null
                        ? "默认模式"
                        : source.getModeSnapshot().getDisplayName())
                .append('\n');
        md.append("- 目标模式: ")
                .append(targetMode == null ? "默认模式" : targetMode.getDisplayName())
                .append('\n');
        if (targetMode != null && targetMode.getDefaultPrompt() != null) {
            md.append("- 目标模式职责: ").append(targetMode.getDefaultPrompt()).append('\n');
        }
        md.append("\n## 首轮目标\n\n");
        md.append(facts.firstUserGoal() == null || facts.firstUserGoal().isBlank()
                ? "（无记录）" : facts.firstUserGoal()).append("\n\n");
        md.append("## 最近对话（尾部 ")
                .append(facts.tailMessages().size()).append(" 条）\n\n");
        if (facts.tailMessages().isEmpty()) {
            md.append("（无记录）\n");
        } else {
            for (HandoffTranscriptFacts.TranscriptMessage message : facts.tailMessages()) {
                md.append("**[").append(message.role()).append("]**: ")
                        .append(abbreviate(message.content(), 4000)).append("\n\n");
            }
        }
        md.append("## 产出 / 修改文件\n\n");
        if (facts.changedFiles().isEmpty()) {
            md.append("（无记录）\n");
        } else {
            for (HandoffTranscriptFacts.TranscriptFileChange change : facts.changedFiles()) {
                md.append("- `").append(change.path()).append("` (")
                        .append(change.changeType()).append(")\n");
            }
        }
        md.append("\n---\n请先完整阅读本交接内容，结合目标模式职责继续任务；"
                + "如信息不足，可回看源会话记录或直接向用户确认。\n");
        return md.toString();
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value
                : value.substring(0, maxLength) + "\n…（已截断）";
    }
}
