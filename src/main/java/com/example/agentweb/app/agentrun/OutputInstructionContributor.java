package com.example.agentweb.app.agentrun;

import org.springframework.stereotype.Component;

/**
 * Placeholder for business-provided output instructions. It contains no hard-coded business template.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
@Component
public class OutputInstructionContributor implements PromptContributor {

    @Override
    public void append(PromptAssembly assembly) {
        String instruction = assembly.getContext().getOutputInstruction();
        if (instruction == null || instruction.trim().isEmpty()) {
            return;
        }
        assembly.addPart(PromptPartType.OUTPUT_INSTRUCTION, "Output Instruction", instruction);
    }
}
