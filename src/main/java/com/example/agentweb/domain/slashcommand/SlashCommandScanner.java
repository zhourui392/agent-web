package com.example.agentweb.domain.slashcommand;

import java.util.List;

/**
 * 扫描工作目录下的 slash 命令定义文件。
 * @author zhourui(V33215020)
 */
public interface SlashCommandScanner {

    /**
     * 扫描指定工作目录及用户主目录下的命令定义，返回所有可用命令。
     *
     * @param workingDir 项目工作目录
     * @return 命令列表，项目级命令优先于主目录级同名命令
     */
    List<SlashCommand> scan(String workingDir);
}
