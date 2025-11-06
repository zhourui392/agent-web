package com.example.agentweb.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "agent.fs")
public class FsProperties {
    private final List<String> roots = new ArrayList<String>();

    public List<String> getRoots() {
        return roots;
    }
}
