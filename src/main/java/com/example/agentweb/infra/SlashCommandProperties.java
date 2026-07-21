package com.example.agentweb.infra;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Slash command 扫描配置.
 * <p>YAML 示例:
 * <pre>
 * agent:
 *   slash-command:
 *     command-dirs:
 *       - .claude/commands
 *     skill-dirs:
 *       - .claude/skills
 *       - .codex/skills
 * </pre>
 * 配置缺失或为空时, 走 {@link FileSlashCommandScanner} 的内置默认值
 * (Claude + Codex 双轨默认扫描)。
 *
 * @author zhourui(V33215020)
 * @since 2026-05-14
 */
@Component
@ConfigurationProperties(prefix = "agent.slash-command")
@Getter
@Setter
public class SlashCommandProperties {

    /** 扫描 commands 目录列表 (相对路径, 同时拼接到 workingDir 与 ~user.home) */
    private List<String> commandDirs;

    /** 扫描 skills 目录列表 (子目录下 SKILL.md 解析为单个命令) */
    private List<String> skillDirs;

    /** 若未配置或为空, 返回 fallback. */
    public List<String> resolveCommandDirs(List<String> fallback) {
        return (commandDirs == null || commandDirs.isEmpty()) ? fallback : commandDirs;
    }

    public List<String> resolveSkillDirs(List<String> fallback) {
        return (skillDirs == null || skillDirs.isEmpty()) ? fallback : skillDirs;
    }
}
