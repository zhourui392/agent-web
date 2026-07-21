package com.example.agentweb.infra;

import com.example.agentweb.domain.slashcommand.SlashCommand;
import com.example.agentweb.domain.slashcommand.SlashCommandScanner;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 基于文件系统的 {@link SlashCommandScanner} 实现.
 * <p>扫描路径分两类:
 * <ul>
 *   <li><b>commandDirs</b>: 目录下的 {@code *.md} 文件 (支持子目录, 用 {@code :} 拼前缀)</li>
 *   <li><b>skillDirs</b>: 目录下的子目录, 每个子目录里的 {@code SKILL.md} 解析为单个命令</li>
 * </ul>
 * <p>默认值同时覆盖 Claude ({@code .claude/commands}, {@code .claude/skills}) 与
 * Codex ({@code .codex/skills}), 通过 {@link SlashCommandProperties} 可在 yml 覆盖。</p>
 * <p>每个目录路径会被拼接到 {@code workingDir} (项目级, 优先) 与 {@code userHome} (主目录级, 同名 fallback)。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-14
 */
@Component
@Slf4j
public class FileSlashCommandScanner implements SlashCommandScanner {

    static final List<String> DEFAULT_COMMAND_DIRS =
            Collections.unmodifiableList(Collections.singletonList(".claude/commands"));
    static final List<String> DEFAULT_SKILL_DIRS =
            Collections.unmodifiableList(Arrays.asList(".claude/skills", ".codex/skills"));

    private static final String FRONTMATTER_DELIMITER = "---";
    private static final String FRONTMATTER_END_MARKER = "\n---";

    private final String userHome;
    private final List<String> commandDirs;
    private final List<String> skillDirs;

    /** Spring 注入路径: 从 yml 读 {@link SlashCommandProperties}, 缺省走内置默认. */
    @Autowired
    public FileSlashCommandScanner(SlashCommandProperties props) {
        this(System.getProperty("user.home"),
                props.resolveCommandDirs(DEFAULT_COMMAND_DIRS),
                props.resolveSkillDirs(DEFAULT_SKILL_DIRS));
    }

    /** 无参构造: 用 system user.home + 默认路径 (测试 / 独立实例化场景). */
    public FileSlashCommandScanner() {
        this(System.getProperty("user.home"), DEFAULT_COMMAND_DIRS, DEFAULT_SKILL_DIRS);
    }

    /** 单参构造: 指定 userHome 但走默认路径 (测试现有用法兼容). */
    public FileSlashCommandScanner(String userHome) {
        this(userHome, DEFAULT_COMMAND_DIRS, DEFAULT_SKILL_DIRS);
    }

    /** 完整构造: 全部参数显式注入 (新增测试 + Spring 注入复用). */
    public FileSlashCommandScanner(String userHome, List<String> commandDirs, List<String> skillDirs) {
        this.userHome = userHome;
        this.commandDirs = (commandDirs == null || commandDirs.isEmpty()) ? DEFAULT_COMMAND_DIRS : commandDirs;
        this.skillDirs = (skillDirs == null || skillDirs.isEmpty()) ? DEFAULT_SKILL_DIRS : skillDirs;
    }

    @Override
    public List<SlashCommand> scan(String workingDir) {
        long startMs = System.currentTimeMillis();
        List<SlashCommand> commands = new ArrayList<SlashCommand>();

        // 项目级别（优先）
        scanAllRoots(commands, workingDir);
        int projectCount = commands.size();

        // 主目录级别（去重，项目级别同名命令优先）
        Set<String> projectNames = collectNames(commands);
        List<SlashCommand> homeCommands = new ArrayList<SlashCommand>();
        scanAllRoots(homeCommands, userHome);

        for (SlashCommand cmd : homeCommands) {
            if (!projectNames.contains(cmd.getName())) {
                commands.add(cmd);
            }
        }
        log.debug("slash-command-scan workingDir={} projectCount={} homeCount={} total={} elapsedMs={}",
                workingDir, projectCount, homeCommands.size(), commands.size(),
                System.currentTimeMillis() - startMs);
        return commands;
    }

    private void scanAllRoots(List<SlashCommand> out, String rootDir) {
        for (String dir : commandDirs) {
            scanCommandsDirectory(Paths.get(rootDir, dir), "", out);
        }
        for (String dir : skillDirs) {
            scanSkillsDirectory(Paths.get(rootDir, dir), out);
        }
    }

    private Set<String> collectNames(List<SlashCommand> commands) {
        Set<String> names = new HashSet<String>();
        for (SlashCommand cmd : commands) {
            names.add(cmd.getName());
        }
        return names;
    }

    private void scanCommandsDirectory(Path dir, String prefix, List<SlashCommand> commands) {
        File[] entries = safeListFiles(dir);
        for (File file : entries) {
            if (file.isDirectory()) {
                String subPrefix = prefix.isEmpty() ? file.getName() : prefix + ":" + file.getName();
                scanCommandsDirectory(file.toPath(), subPrefix, commands);
            } else if (file.getName().endsWith(".md")) {
                String commandName = buildCommandName(prefix, file.getName());
                commands.add(parseCommandFile(file, commandName, false));
            }
        }
    }

    private void scanSkillsDirectory(Path skillsDir, List<SlashCommand> commands) {
        File[] entries = safeListFiles(skillsDir);
        for (File subDir : entries) {
            if (!subDir.isDirectory()) {
                continue;
            }
            File skillFile = new File(subDir, "SKILL.md");
            if (skillFile.isFile()) {
                commands.add(parseCommandFile(skillFile, subDir.getName(), true));
            }
        }
    }

    private String buildCommandName(String prefix, String fileName) {
        String baseName = fileName.replace(".md", "");
        return prefix.isEmpty() ? baseName : prefix + ":" + baseName;
    }

    private File[] safeListFiles(Path dir) {
        File directory = dir.toFile();
        if (!directory.isDirectory()) {
            return new File[0];
        }
        File[] files = directory.listFiles();
        return files != null ? files : new File[0];
    }

    private SlashCommand parseCommandFile(File file, String fallbackName, boolean skill) {
        Map<String, String> frontmatter = parseFrontmatter(file);
        String name = frontmatter.getOrDefault("name", fallbackName);
        String description = frontmatter.getOrDefault("description", "");
        String argumentHint = frontmatter.getOrDefault("argument-hint", "");
        String body = readCommandBody(file);
        return new SlashCommand(name, description, argumentHint, body, skill);
    }

    private String readCommandBody(File file) {
        String content = readFileContent(file.toPath());
        return stripFrontmatter(content).trim();
    }

    private String readFileContent(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private String stripFrontmatter(String content) {
        if (!content.startsWith(FRONTMATTER_DELIMITER)) {
            return content;
        }
        int endIndex = content.indexOf(FRONTMATTER_END_MARKER, FRONTMATTER_DELIMITER.length());
        return endIndex < 0 ? content : content.substring(endIndex + FRONTMATTER_END_MARKER.length() + 1);
    }

    private Map<String, String> parseFrontmatter(File file) {
        Map<String, String> result = new HashMap<>(16);
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            try {
                String firstLine = reader.readLine();
                if (firstLine == null || !FRONTMATTER_DELIMITER.equals(firstLine.trim())) {
                    return result;
                }
                String line;
                while ((line = reader.readLine()) != null) {
                    if (FRONTMATTER_DELIMITER.equals(line.trim())) {
                        break;
                    }
                    int colonIndex = line.indexOf(':');
                    if (colonIndex > 0) {
                        String key = line.substring(0, colonIndex).trim();
                        String value = line.substring(colonIndex + 1).trim();
                        result.put(key, value);
                    }
                }
            } finally {
                reader.close();
            }
        } catch (Exception e) {
            // 解析失败时返回空 map，使用 fallback 值
            log.warn("slash-command-frontmatter-parse-failed file={} reason={}",
                    file.getAbsolutePath(), e.getMessage());
        }
        return result;
    }
}
