package com.example.agentweb.app.mode;

import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.mode.ChatMode;
import com.example.agentweb.domain.mode.ChatModeRepository;
import com.example.agentweb.domain.mode.ModeCapabilities;
import com.example.agentweb.domain.mode.ModeNotFoundException;
import com.example.agentweb.domain.mode.ModePermissionMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 模式用例编排：CRUD 与模板 fork。
 *
 * <p>引用校验、归属校验等业务语义在 {@link ChatMode} 聚合与
 * {@link ModeAccessInput} 领域转换器内；本层只做仓储编排与模板事实拷贝。</p>
 *
 * @author alex
 * @since 2026-08-20
 */
@Service
@Slf4j
public class ModeAppServiceImpl implements ModeAppService {

    private final ChatModeRepository modeRepository;
    private final ModeQueryService modeQueryService;
    private final CurrentUserProvider currentUserProvider;

    public ModeAppServiceImpl(ChatModeRepository modeRepository,
                              ModeQueryService modeQueryService,
                              CurrentUserProvider currentUserProvider) {
        this.modeRepository = modeRepository;
        this.modeQueryService = modeQueryService;
        this.currentUserProvider = currentUserProvider;
    }

    @Override
    public ModeView create(SaveModeCommand command) {
        String userId = requireUserId();
        ChatMode mode = ChatMode.create(
                UUID.randomUUID().toString(), userId,
                command.identifier(), command.displayName(), command.description(),
                command.defaultPrompt(), ModePermissionMode.fromCliValue(command.permissionMode()),
                command.model(), command.effort(), null,
                toCapabilities(command.commands(), command.skills(), command.mcpServers()),
                Instant.now());
        modeRepository.save(mode);
        log.info("chat-mode-created modeId={} identifier={} userId={}",
                mode.getId(), mode.getIdentifier(), userId);
        return ModeView.from(mode);
    }

    @Override
    public ModeView update(String modeId, SaveModeCommand command) {
        String userId = requireUserId();
        ChatMode mode = loadOwned(modeId, userId);
        mode.edit(command.displayName(), command.description(), command.defaultPrompt(),
                ModePermissionMode.fromCliValue(command.permissionMode()),
                command.model(), command.effort(),
                toCapabilities(command.commands(), command.skills(), command.mcpServers()),
                Instant.now());
        modeRepository.save(mode);
        log.info("chat-mode-updated modeId={} version={}", mode.getId(), mode.getVersion());
        return ModeView.from(mode);
    }

    @Override
    public void delete(String modeId) {
        String userId = requireUserId();
        ChatMode mode = loadOwned(modeId, userId);
        modeRepository.delete(mode.getId(), mode.getVersion());
    }

    @Override
    public ModeView fork(String definitionIdentifier, long revisionId, String displayName) {
        String userId = requireUserId();
        ModeQueryService.ModeTemplateRevisionView revision =
                modeQueryService.findTemplateRevision(definitionIdentifier, revisionId);
        if (revision == null) {
            throw new ModeNotFoundException(definitionIdentifier + ":" + revisionId);
        }
        List<SaveModeCommand.CapabilityRefInput> commands = revision.commands().stream()
                .map(ref -> new SaveModeCommand.CapabilityRefInput(
                        ref.identifier(), ref.version(), ref.contentHash(), 0))
                .toList();
        List<SaveModeCommand.CapabilityRefInput> skills = revision.skills().stream()
                .map(ref -> new SaveModeCommand.CapabilityRefInput(
                        ref.identifier(), ref.version(), ref.contentHash(), 0))
                .toList();
        List<SaveModeCommand.McpServerRefInput> mcpServers = revision.mcpServers().stream()
                .map(ref -> new SaveModeCommand.McpServerRefInput(
                        ref.identifier(), ref.version(), ref.contentHash(),
                        ref.maximumAccess(), ref.transport(), 0))
                .toList();
        String resolvedDisplayName = displayName == null || displayName.trim().isEmpty()
                ? revision.displayName() : displayName.trim();
        ChatMode mode = ChatMode.create(
                UUID.randomUUID().toString(), userId,
                "fork-" + revision.definitionIdentifier() + "-" + UUID.randomUUID()
                        .toString().substring(0, 8),
                resolvedDisplayName, revision.description(), revision.stageRules(),
                null, null, null, revisionId,
                toCapabilities(commands, skills, mcpServers),
                Instant.now());
        modeRepository.save(mode);
        log.info("chat-mode-forked modeId={} fromRevision={} userId={}",
                mode.getId(), revisionId, userId);
        return ModeView.from(mode);
    }

    private ChatMode loadOwned(String modeId, String userId) {
        ChatMode mode = modeRepository.find(modeId)
                .orElseThrow(() -> new ModeNotFoundException(modeId));
        if (!currentUserProvider.isAdministrator()) {
            mode.requireOwnedBy(userId);
        }
        return mode;
    }

    private String requireUserId() {
        String userId = currentUserProvider.currentUserId();
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("mode operations require an authenticated user");
        }
        return userId;
    }

    /** 应用层 DTO 引用 → 领域能力引用（纯映射，不变量由 ModeCapabilities 构造期校验）。 */
    private static ModeCapabilities toCapabilities(
            List<SaveModeCommand.CapabilityRefInput> commands,
            List<SaveModeCommand.CapabilityRefInput> skills,
            List<SaveModeCommand.McpServerRefInput> mcpServers) {
        return new ModeCapabilities(
                commands == null ? List.of() : commands.stream()
                        .map(r -> new ModeCapabilities.CommandRef(
                                r.identifier(), r.version(), r.contentHash(), r.sortOrder()))
                        .toList(),
                skills == null ? List.of() : skills.stream()
                        .map(r -> new ModeCapabilities.SkillRef(
                                r.identifier(), r.version(), r.contentHash(), r.sortOrder()))
                        .toList(),
                mcpServers == null ? List.of() : mcpServers.stream()
                        .map(r -> new ModeCapabilities.McpServerRef(
                                r.identifier(), r.version(), r.contentHash(),
                                r.maximumAccess(), r.transport(), r.sortOrder()))
                        .toList());
    }
}
