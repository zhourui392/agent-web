package com.example.agentweb.domain.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 将 Agent 声明的 TDD 阶段与 Runtime 真实命令、退出码逐项对账后生成证据。
 *
 * @author alex
 * @since 2026-07-23
 */
public final class ImplementationCommandEvidenceFactory {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public RuntimeArtifactBundle enrich(RuntimeArtifactBundle bundle,
                                        List<RuntimeCommandObservation> observations) {
        bundle.requireStage(HarnessStage.IMPLEMENTATION);
        List<RuntimeCommandObservation> ordered = ordered(observations);
        RuntimeProducedArtifact declared = bundle.artifact(ArtifactType.TEST_EVIDENCE);
        JsonNode commands = declaredCommands(declared);
        ObjectNode evidence = MAPPER.createObjectNode();
        ArrayNode verified = evidence.putArray("commands");
        int observationIndex = 0;
        for (JsonNode command : commands) {
            String expectedCommand = requiredText(command, "command");
            String phase = requiredPhase(command);
            int declaredExitCode = requiredExitCode(command);
            RuntimeCommandObservation observation = null;
            while (observationIndex < ordered.size()) {
                RuntimeCommandObservation candidate = ordered.get(observationIndex++);
                if (expectedCommand.equals(candidate.getCommand())) {
                    observation = candidate;
                    break;
                }
            }
            if (observation == null || declaredExitCode != observation.getExitCode()) {
                throw new IllegalArgumentException(
                        "declared test command does not match runtime observation");
            }
            ObjectNode item = verified.addObject();
            item.put("commandId", command.path("commandId").isTextual()
                    && !command.path("commandId").asText().trim().isEmpty()
                    ? command.path("commandId").asText()
                    : "runtime-command-" + observation.getSequence());
            item.put("command", observation.getCommand());
            item.put("phase", phase);
            item.put("exitCode", observation.getExitCode());
            item.put("outputHash", observation.getOutputHash());
            item.put("runtimeSequence", observation.getSequence());
            item.put("runtimeObserved", true);
        }
        RuntimeProducedArtifact replacement = new RuntimeProducedArtifact(
                "implementation-test-evidence", ArtifactType.TEST_EVIDENCE,
                "application/json", ArtifactClassification.INTERNAL, content(evidence));
        return bundle.replace(replacement);
    }

    private List<RuntimeCommandObservation> ordered(List<RuntimeCommandObservation> observations) {
        if (observations == null || observations.isEmpty() || observations.contains(null)) {
            throw new IllegalArgumentException("runtime command observations are required");
        }
        List<RuntimeCommandObservation> values =
                new ArrayList<RuntimeCommandObservation>(observations);
        values.sort(Comparator.comparingInt(RuntimeCommandObservation::getSequence));
        Set<Integer> sequences = new HashSet<Integer>();
        for (RuntimeCommandObservation observation : values) {
            if (!sequences.add(Integer.valueOf(observation.getSequence()))) {
                throw new IllegalArgumentException("runtime command sequences must be unique");
            }
        }
        return values;
    }

    private JsonNode declaredCommands(RuntimeProducedArtifact declared) {
        try {
            JsonNode root = MAPPER.readTree(declared.getContent().copyBytes());
            JsonNode commands = root == null ? null : root.path("commands");
            if (commands == null || !commands.isArray() || commands.isEmpty()) {
                throw new IllegalArgumentException("declared test commands are required");
            }
            return commands;
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("declared test evidence is invalid", ex);
        }
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().trim().isEmpty()) {
            throw new IllegalArgumentException("declared test command field is invalid: " + field);
        }
        return value.asText();
    }

    private String requiredPhase(JsonNode command) {
        String phase = requiredText(command, "phase");
        if (!"RED".equals(phase) && !"GREEN".equals(phase) && !"VERIFY".equals(phase)) {
            throw new IllegalArgumentException("declared test command phase is invalid");
        }
        return phase;
    }

    private int requiredExitCode(JsonNode command) {
        JsonNode exitCode = command.get("exitCode");
        if (exitCode == null || !exitCode.canConvertToInt()) {
            throw new IllegalArgumentException("declared test command exit code is invalid");
        }
        return exitCode.asInt();
    }

    private ArtifactContent content(ObjectNode node) {
        try {
            return ArtifactContent.from(MAPPER.writeValueAsString(node)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("verified test evidence could not be generated", ex);
        }
    }
}
