package com.example.agentweb.domain.slashcommand;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 识别用户消息中的 slash 命令并展开为完整的提示词模板。
 * @author zhourui(V33215020)
 */
@Slf4j
public class SlashCommandExpander {

    private static final String SLASH_PREFIX = "/";

    private final SlashCommandScanner scanner;

    public SlashCommandExpander(SlashCommandScanner scanner) {
        this.scanner = scanner;
    }

    /**
     * 若消息以 "/" 开头且匹配已注册的命令，则展开为模板内容并替换 $ARGUMENTS；否则原样返回。
     *
     * @param workingDir 项目工作目录
     * @param message    用户输入的原始消息
     * @return 展开后的消息，或原始消息
     */
    public String expandIfCommand(String workingDir, String message) {
        if (message == null || !message.startsWith(SLASH_PREFIX)) {
            return message;
        }

        String commandName = extractCommandName(message);
        String arguments = extractArguments(message);

        SlashCommand matched = findCommand(workingDir, commandName);
        if (matched == null) {
            log.debug("slash-command-no-match commandName={} workingDir={}", commandName, workingDir);
            return message;
        }

        String expanded = matched.getBody().replace("$ARGUMENTS", arguments);
        log.info("slash-command-expanded commandName={} argsLen={} bodyLen={} expandedLen={}",
                commandName,
                arguments.length(),
                matched.getBody() == null ? 0 : matched.getBody().length(),
                expanded.length());
        return expanded;
    }

    /**
     * 列出指定工作目录下所有可用的 slash 命令（不含 skills）。
     *
     * @param workingDir 项目工作目录
     * @return 非 skill 类型的命令列表
     */
    public List<SlashCommand> listCommands(String workingDir) {
        List<SlashCommand> all = scanner.scan(workingDir);
        List<SlashCommand> commands = new ArrayList<SlashCommand>();
        for (SlashCommand cmd : all) {
            if (!cmd.isSkill()) {
                commands.add(cmd);
            }
        }
        return commands;
    }

    private String extractCommandName(String message) {
        int spaceIndex = message.indexOf(' ');
        return spaceIndex > 0 ? message.substring(1, spaceIndex) : message.substring(1);
    }

    private String extractArguments(String message) {
        int spaceIndex = message.indexOf(' ');
        return spaceIndex > 0 ? message.substring(spaceIndex + 1).trim() : "";
    }

    private SlashCommand findCommand(String workingDir, String commandName) {
        List<SlashCommand> commands = scanner.scan(workingDir);
        for (SlashCommand cmd : commands) {
            if (cmd.getName().equals(commandName)) {
                return cmd;
            }
        }
        return null;
    }
}
