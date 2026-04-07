package com.example.agentweb.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "agent")
public class EnvProperties {

    private List<EnvEntry> envs = new ArrayList<>();

    public List<EnvEntry> getEnvs() {
        return envs;
    }

    public void setEnvs(List<EnvEntry> envs) {
        this.envs = envs;
    }

    public EnvEntry findByKey(String key) {
        for (EnvEntry e : envs) {
            if (e.getKey().equals(key)) {
                return e;
            }
        }
        return null;
    }

    public static class EnvEntry {
        private String key;
        private String label;
        private String color;
        private String prompt;

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }

        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }

        public String getPrompt() { return prompt; }
        public void setPrompt(String prompt) { this.prompt = prompt; }
    }
}
