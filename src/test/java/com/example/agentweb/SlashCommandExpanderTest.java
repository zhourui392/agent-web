package com.example.agentweb;

import com.example.agentweb.domain.SlashCommand;
import com.example.agentweb.domain.SlashCommandExpander;
import com.example.agentweb.domain.SlashCommandScanner;
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

import static org.junit.jupiter.api.Assertions.*;

public class SlashCommandExpanderTest {

    private Path tempDir;
    private SlashCommandExpander expander;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("slash-expand-test");
        SlashCommandScanner scanner = new FileSlashCommandScanner();
        expander = new SlashCommandExpander(scanner);
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
    }

    @Test
    void shouldExpandCommandWithArguments() throws IOException {
        Path commandsDir = tempDir.resolve(".claude/commands");
        Files.createDirectories(commandsDir);

        String template = "---\nname: greet\n---\nHello $ARGUMENTS, welcome!";
        writeFile(commandsDir.resolve("greet.md"), template);

        String result = expander.expandIfCommand(tempDir.toString(), "/greet world");

        assertEquals("Hello world, welcome!", result);
    }

    @Test
    void shouldReturnOriginalWhenCommandNotFound() {
        String result = expander.expandIfCommand(tempDir.toString(), "/nonexistent some args");
        assertEquals("/nonexistent some args", result);
    }

    @Test
    void shouldReturnOriginalWhenNotSlashCommand() {
        String result = expander.expandIfCommand(tempDir.toString(), "just a normal message");
        assertEquals("just a normal message", result);
    }

    private void writeFile(Path path, String content) throws IOException {
        Files.write(path, Collections.singletonList(content), StandardCharsets.UTF_8);
    }
}
