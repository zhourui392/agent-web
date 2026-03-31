package com.example.agentweb.domain;

/**
 * 用户自定义的 slash 命令定义，包含命令元信息和模板内容。
 */
public class SlashCommand {

    private final String name;
    private final String description;
    private final String argumentHint;
    private final String body;
    private final boolean skill;

    /**
     * @param name         命令名称，如 "deploy" 或 "spec:bizflow"
     * @param description  命令描述
     * @param argumentHint 参数提示，如 "[env]"
     * @param body         命令模板内容（已去除 frontmatter），可包含 $ARGUMENTS 占位符
     * @param skill        是否来源于 skills 目录
     */
    public SlashCommand(String name, String description, String argumentHint, String body, boolean skill) {
        this.name = name;
        this.description = description;
        this.argumentHint = argumentHint;
        this.body = body;
        this.skill = skill;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getArgumentHint() {
        return argumentHint;
    }

    /**
     * @return 命令模板内容（已去除 frontmatter）
     */
    public String getBody() {
        return body;
    }

    /**
     * @return 是否来源于 skills 目录
     */
    public boolean isSkill() {
        return skill;
    }
}
