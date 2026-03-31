package com.example.agentweb.infra;

import com.example.agentweb.domain.SlashCommand;
import com.example.agentweb.domain.SlashCommandScanner;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * 基于文件系统的 {@link SlashCommandScanner} 实现。
 * <p>扫描 {@code .claude/commands/} 和 {@code .claude/skills/} 目录下的 Markdown 命令定义文件，
 * 解析 YAML frontmatter 并读取模板内容。</p>
 */
@Component
public class FileSlashCommandScanner implements SlashCommandScanner {

    private static final String FRONTMATTER_DELIMITER = "---";
    private static final String FRONTMATTER_END_MARKER = "\n---";

    private final String userHome;

    public FileSlashCommandScanner() {
        this(System.getProperty("user.home"));
    }

    public FileSlashCommandScanner(String userHome) {
        this.userHome = userHome;
    }

    @Override
    public List<SlashCommand> scan(String workingDir) {
        List<SlashCommand> commands = new ArrayList<SlashCommand>();

        // 项目级别（优先）
        scanCommandsDirectory(Paths.get(workingDir, ".claude", "commands"), "", commands);
        scanSkillsDirectory(Paths.get(workingDir, ".claude", "skills"), commands);

        // 主目录级别（去重，项目级别同名命令优先）
        Set<String> projectNames = collectNames(commands);
        List<SlashCommand> homeCommands = new ArrayList<SlashCommand>();
        scanCommandsDirectory(Paths.get(userHome, ".claude", "commands"), "", homeCommands);
        scanSkillsDirectory(Paths.get(userHome, ".claude", "skills"), homeCommands);

        for (SlashCommand cmd : homeCommands) {
            if (!projectNames.contains(cmd.getName())) {
                commands.add(cmd);
            }
        }
        return commands;
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
        Map<String, String> result = new HashMap<String, String>();
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            try {
                String firstLine = reader.readLine();
                if (firstLine == null || !"---".equals(firstLine.trim())) {
                    return result;
                }
                String line;
                while ((line = reader.readLine()) != null) {
                    if ("---".equals(line.trim())) {
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
        }
        return result;
    }
}
