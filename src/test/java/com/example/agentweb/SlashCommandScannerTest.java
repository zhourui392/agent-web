package com.example.agentweb;

import com.example.agentweb.domain.slashcommand.SlashCommand;
import com.example.agentweb.domain.slashcommand.SlashCommandScanner;
import com.example.agentweb.infra.FileSlashCommandScanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SlashCommandScannerTest {

    private Path tempDir;
    private Path fakeHome;
    private SlashCommandScanner scanner;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("slash-cmd-test");
        fakeHome = Files.createTempDirectory("slash-cmd-home");
        scanner = new FileSlashCommandScanner(fakeHome.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        Files.walk(fakeHome)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
    }

    @Test
    void shouldScanCommandsFromClaudeCommandsDirectory() throws IOException {
        Path commandsDir = tempDir.resolve(".claude/commands");
        Files.createDirectories(commandsDir);

        writeFile(commandsDir.resolve("git-commit.md"),
                "提交代码并推送到远程仓库\n\n$ARGUMENTS");
        writeFile(commandsDir.resolve("review.md"),
                "Review the code changes\n\n$ARGUMENTS");

        List<SlashCommand> commands = scanner.scan(tempDir.toString());

        assertEquals(2, commands.size());
        assertTrue(commands.stream().anyMatch(c -> "git-commit".equals(c.getName())));
        assertTrue(commands.stream().anyMatch(c -> "review".equals(c.getName())));
    }

    @Test
    void shouldParseFrontmatterFields() throws IOException {
        Path commandsDir = tempDir.resolve(".claude/commands");
        Files.createDirectories(commandsDir);

        String content = "---\n"
                + "name: deploy\n"
                + "description: Deploy to target environment\n"
                + "argument-hint: [env]\n"
                + "---\n"
                + "\nDeploy $ARGUMENTS to production.";
        writeFile(commandsDir.resolve("deploy.md"), content);

        List<SlashCommand> commands = scanner.scan(tempDir.toString());

        assertEquals(1, commands.size());
        SlashCommand cmd = commands.get(0);
        assertEquals("deploy", cmd.getName());
        assertEquals("Deploy to target environment", cmd.getDescription());
        assertEquals("[env]", cmd.getArgumentHint());
    }

    @Test
    void shouldScanSubdirectoryCommandsWithColonSeparator() throws IOException {
        Path subDir = tempDir.resolve(".claude/commands/spec");
        Files.createDirectories(subDir);

        writeFile(subDir.resolve("bizflow.md"), "业务流分析\n\n$ARGUMENTS");
        writeFile(subDir.resolve("tech-spec.md"), "技术方案生成\n\n$ARGUMENTS");

        List<SlashCommand> commands = scanner.scan(tempDir.toString());

        assertEquals(2, commands.size());
        assertTrue(commands.stream().anyMatch(c -> "spec:bizflow".equals(c.getName())));
        assertTrue(commands.stream().anyMatch(c -> "spec:tech-spec".equals(c.getName())));
    }

    @Test
    void shouldScanSkillsDirectory() throws IOException {
        Path skillDir = tempDir.resolve(".claude/skills/code-review");
        Files.createDirectories(skillDir);

        String content = "---\n"
                + "name: code-review\n"
                + "description: Review code for quality\n"
                + "---\n"
                + "\nReview $ARGUMENTS for issues.";
        writeFile(skillDir.resolve("SKILL.md"), content);

        List<SlashCommand> commands = scanner.scan(tempDir.toString());

        assertEquals(1, commands.size());
        assertEquals("code-review", commands.get(0).getName());
        assertEquals("Review code for quality", commands.get(0).getDescription());
    }

    @Test
    void shouldScanHomeDirectoryCommands() throws IOException {
        Path homeCommands = fakeHome.resolve(".claude/commands");
        Files.createDirectories(homeCommands);
        writeFile(homeCommands.resolve("global-cmd.md"), "A global command\n\n$ARGUMENTS");

        List<SlashCommand> commands = scanner.scan(tempDir.toString());

        assertEquals(1, commands.size());
        assertEquals("global-cmd", commands.get(0).getName());
    }

    @Test
    void shouldDeduplicateProjectOverHome() throws IOException {
        Path projectCmds = tempDir.resolve(".claude/commands");
        Files.createDirectories(projectCmds);
        writeFile(projectCmds.resolve("deploy.md"),
                "---\ndescription: project deploy\n---\n$ARGUMENTS");

        Path homeCmds = fakeHome.resolve(".claude/commands");
        Files.createDirectories(homeCmds);
        writeFile(homeCmds.resolve("deploy.md"),
                "---\ndescription: home deploy\n---\n$ARGUMENTS");

        List<SlashCommand> commands = scanner.scan(tempDir.toString());

        long deployCount = commands.stream().filter(c -> "deploy".equals(c.getName())).count();
        assertEquals(1, deployCount);

        SlashCommand deploy = commands.stream()
                .filter(c -> "deploy".equals(c.getName())).findFirst().get();
        assertEquals("project deploy", deploy.getDescription());
    }

    @Test
    void shouldReturnEmptyListWhenNoCommandsDirectory() {
        List<SlashCommand> commands = scanner.scan(tempDir.toString());
        assertTrue(commands.isEmpty());
    }

    @Test
    void shouldScanCodexSkillsDirectoryByDefault() throws IOException {
        // 默认扫描路径除 .claude/skills 外也应包含 .codex/skills (Codex 切换基线)
        Path codexSkill = tempDir.resolve(".codex/skills/list-files");
        Files.createDirectories(codexSkill);

        String content = "---\n"
                + "name: list-files\n"
                + "description: 列出当前目录\n"
                + "---\n"
                + "\nRun `ls -la`.";
        writeFile(codexSkill.resolve("SKILL.md"), content);

        List<SlashCommand> commands = scanner.scan(tempDir.toString());

        assertEquals(1, commands.size());
        assertEquals("list-files", commands.get(0).getName());
    }

    @Test
    void shouldRespectCustomSkillDirsConfiguration() throws IOException {
        // 自定义扫描配置: 关闭 .codex/skills, 只看 .claude/skills, 验证 .codex/skills 被忽略
        scanner = new FileSlashCommandScanner(
                fakeHome.toString(),
                Collections.singletonList(".claude/commands"),
                Collections.singletonList(".claude/skills"));

        Path codexSkill = tempDir.resolve(".codex/skills/wont-be-scanned");
        Files.createDirectories(codexSkill);
        writeFile(codexSkill.resolve("SKILL.md"),
                "---\nname: wont-be-scanned\ndescription: x\n---\n\nbody");

        Path claudeSkill = tempDir.resolve(".claude/skills/will-be-scanned");
        Files.createDirectories(claudeSkill);
        writeFile(claudeSkill.resolve("SKILL.md"),
                "---\nname: will-be-scanned\ndescription: y\n---\n\nbody");

        List<SlashCommand> commands = scanner.scan(tempDir.toString());

        assertEquals(1, commands.size());
        assertEquals("will-be-scanned", commands.get(0).getName());
    }

    @Test
    void shouldScanCodexSkillsFromHomeDirectory() throws IOException {
        // ~/.codex/skills/ 也走主目录扫描通路, 同名 fallback 给项目级让位
        Path homeCodex = fakeHome.resolve(".codex/skills/global-codex-skill");
        Files.createDirectories(homeCodex);
        writeFile(homeCodex.resolve("SKILL.md"),
                "---\nname: global-codex-skill\ndescription: home-level codex skill\n---\n\nbody");

        List<SlashCommand> commands = scanner.scan(tempDir.toString());

        assertEquals(1, commands.size());
        assertEquals("global-codex-skill", commands.get(0).getName());
        assertEquals("home-level codex skill", commands.get(0).getDescription());
    }

    private void writeFile(Path path, String content) throws IOException {
        Files.write(path, Collections.singletonList(content), StandardCharsets.UTF_8);
    }
}
