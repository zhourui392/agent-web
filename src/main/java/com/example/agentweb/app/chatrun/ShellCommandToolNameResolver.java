package com.example.agentweb.app.chatrun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ShellCommandToolNameResolver {
    private static final Pattern TOKEN = Pattern.compile("(?:'([^']*)'|\"((?:\\\\.|[^\"])*)\"|([^\\s;|&]+))");
    private static final Pattern ASSIGNMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*=.*");
    private static final Pattern COMPLEX_SHELL_SCRIPT = Pattern.compile(
            "(^|[;\\s])(for|while|until|select)\\s+.+\\s+do([;\\s]|$)"
                    + "|(^|[;\\s])if\\s+.+\\s+then([;\\s]|$)"
                    + "|(^|[;\\s])case\\s+.+\\s+in([;\\s]|$)"
                    + "|(^|[;\\s])function\\s+[A-Za-z_][A-Za-z0-9_]*"
                    + "|(^|[;\\s])[A-Za-z_][A-Za-z0-9_]*\\s*\\(\\s*\\)\\s*\\{"
                    + "|`[^`]+`|\\$\\([^)]*\\)", Pattern.DOTALL);
    private static final Set<String> SHELLS = new HashSet<String>(Arrays.asList(
            "bash", "sh", "zsh", "dash", "ksh", "cmd", "cmd.exe", "powershell", "powershell.exe", "pwsh"));
    private static final Set<String> PREFIXES = new HashSet<String>(Arrays.asList(
            "env", "sudo", "command", "nohup", "time", "nice", "xargs"));

    private final ObjectMapper mapper;

    public ShellCommandToolNameResolver(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String resolveInputJson(String inputJson) {
        if (inputJson == null || inputJson.trim().isEmpty()) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(inputJson);
            return resolveCommand(root.path("command").asText(null));
        } catch (Exception ignored) {
            return null;
        }
    }

    public String resolveCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return null;
        }
        String candidate = command.trim();
        for (int depth = 0; depth < 4; depth++) {
            Matcher matcher = TOKEN.matcher(candidate);
            if (!matcher.find()) {
                return null;
            }
            String token = token(matcher);
            String executable = basename(token);
            if (SHELLS.contains(executable)) {
                String remainder = candidate.substring(matcher.end()).trim();
                remainder = remainder.replaceFirst("^-(?:l?c|c)\\s+", "").trim();
                String script = unwrap(remainder);
                if (isComplexShellScript(script)) {
                    return executable;
                }
                candidate = script;
                continue;
            }
            if ("cd".equals(executable)) {
                int separator = commandSeparator(candidate, matcher.end());
                if (separator < 0) {
                    return null;
                }
                candidate = candidate.substring(separator).replaceFirst("^[;&|\\s]+", "");
                continue;
            }
            if (ASSIGNMENT.matcher(token).matches() || PREFIXES.contains(executable)) {
                candidate = candidate.substring(matcher.end()).trim();
                continue;
            }
            if (token.startsWith("-")) {
                candidate = candidate.substring(matcher.end()).trim();
                continue;
            }
            return executable.isEmpty() ? null : executable;
        }
        return null;
    }

    private boolean isComplexShellScript(String script) {
        return COMPLEX_SHELL_SCRIPT.matcher(script).find();
    }

    private int commandSeparator(String value, int from) {
        int and = value.indexOf("&&", from);
        int semicolon = value.indexOf(';', from);
        if (and < 0) return semicolon;
        if (semicolon < 0) return and;
        return Math.min(and, semicolon);
    }

    private String unwrap(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private String token(Matcher matcher) {
        if (matcher.group(1) != null) return matcher.group(1);
        if (matcher.group(2) != null) return matcher.group(2).replace("\\\"", "\"");
        return matcher.group(3);
    }

    private String basename(String value) {
        String normalized = value.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }
}
