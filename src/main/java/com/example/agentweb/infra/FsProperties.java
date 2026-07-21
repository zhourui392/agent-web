package com.example.agentweb.infra;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * @author zhourui(V33215020)
 */
@Component
@ConfigurationProperties(prefix = "agent.fs")
@Getter
public class FsProperties {
    private final List<String> roots = new ArrayList<String>();

    /**
     * 仅对 upload 接口额外放行的根目录;download/delete/list 不受影响。
     * 用于允许把文件写入某目录,但不暴露目录内容被下载。
     */
    private final List<String> uploadRoots = new ArrayList<String>();
}
