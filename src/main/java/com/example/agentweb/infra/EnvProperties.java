package com.example.agentweb.infra;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * @author zhourui(V33215020)
 */
@Component
@ConfigurationProperties(prefix = "agent")
@Getter
@Setter
public class EnvProperties {

    private List<EnvEntry> envs = new ArrayList<>();

    public EnvEntry findByKey(String key) {
        for (EnvEntry e : envs) {
            if (e.getKey().equals(key)) {
                return e;
            }
        }
        return null;
    }

    @Getter
    @Setter
    public static class EnvEntry {
        private String key;
        private String label;
        private String color;
        private String prompt;
    }
}
