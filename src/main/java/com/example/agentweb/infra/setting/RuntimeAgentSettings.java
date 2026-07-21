package com.example.agentweb.infra.setting;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.infra.AgentDefaultProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 运行时"默认模型"开关:对话默认 agent,管理后台可改、免重启热生效。
 *
 * <p>读取优先级:DB({@link AppSettingRepository})> yml 种子
 * ({@link AgentDefaultProperties})> 硬兜底 {@link #HARD_DEFAULT}。
 * yml 仅在首次(DB 无值)生效,落库后以 DB 为准。</p>
 *
 * <p>值缓存到内存,写入后立即刷新;读取为无锁字段访问。不在 {@code @PostConstruct} 读库,
 * 改为首个 getter 触发懒加载,规避与建表初始化的启动顺序耦合。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-25
 */
@Component
@Slf4j
public class RuntimeAgentSettings {

    /** 任何上游均缺失/非法时的最终兜底,与前端硬编码默认对齐。 */
    private static final AgentType HARD_DEFAULT = AgentType.CLAUDE;

    static final String KEY_CHAT_AGENT = "chat.default-agent";
    static final String KEY_CHAT_AGENT_VERSION = "chat.default-agent.version";

    private final AppSettingRepository repo;
    private final AgentDefaultProperties chatDefaults;

    private volatile boolean loaded;
    private volatile AgentType chatDefaultAgent;
    private volatile long chatDefaultAgentVersion;

    public RuntimeAgentSettings(AppSettingRepository repo,
                                AgentDefaultProperties chatDefaults) {
        this.repo = repo;
        this.chatDefaults = chatDefaults;
    }

    /** 对话默认 agent(请求未显式指定时的兜底,亦供前端预选)。 */
    public AgentType getChatDefaultAgent() {
        ensureLoaded();
        return chatDefaultAgent;
    }

    /** 对话默认 agent 的版本号:每次后台变更自增,驱动前端"强制全员跟随"一次性重置。 */
    public long getChatDefaultAgentVersion() {
        ensureLoaded();
        return chatDefaultAgentVersion;
    }

    /**
     * 设置对话默认 agent 并自增版本号。
     *
     * @param agent 新的默认 agent(须可选)
     */
    public synchronized void setChatDefaultAgent(AgentType agent) {
        ensureLoaded();
        long now = System.currentTimeMillis();
        long nextVersion = chatDefaultAgentVersion + 1;
        repo.put(KEY_CHAT_AGENT, agent.name(), now);
        repo.put(KEY_CHAT_AGENT_VERSION, Long.toString(nextVersion), now);
        this.chatDefaultAgent = agent;
        this.chatDefaultAgentVersion = nextVersion;
        log.info("runtime-setting-updated key={} value={} version={}", KEY_CHAT_AGENT, agent, nextVersion);
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (this) {
            if (loaded) {
                return;
            }
            this.chatDefaultAgent = readAgent(KEY_CHAT_AGENT, seedChatAgent());
            this.chatDefaultAgentVersion = readVersion();
            this.loaded = true;
        }
    }

    private AgentType readAgent(String key, AgentType fallback) {
        Optional<String> raw = repo.get(key);
        if (!raw.isPresent()) {
            return fallback;
        }
        try {
            return AgentType.parseSelectable(raw.get());
        } catch (IllegalArgumentException e) {
            log.warn("runtime-setting-invalid key={} value={} fallback={}", key, raw.get(), fallback);
            return fallback;
        }
    }

    private long readVersion() {
        Optional<String> raw = repo.get(KEY_CHAT_AGENT_VERSION);
        if (!raw.isPresent()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.get().trim());
        } catch (NumberFormatException e) {
            log.warn("runtime-setting-invalid-version value={}", raw.get());
            return 0L;
        }
    }

    private AgentType seedChatAgent() {
        return sanitize(chatDefaults.getDefaultType());
    }

    private AgentType sanitize(AgentType candidate) {
        return candidate != null && candidate.isSelectable() ? candidate : HARD_DEFAULT;
    }
}
