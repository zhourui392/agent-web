package com.example.agentweb.app.chatrun;

import lombok.Getter;

@Getter
public class PreparedChatRunPrompt {
    private final String prompt;
    private final ExplicitSkillInvocation explicitSkillInvocation;

    public PreparedChatRunPrompt(String prompt, ExplicitSkillInvocation explicitSkillInvocation) {
        this.prompt = prompt;
        this.explicitSkillInvocation = explicitSkillInvocation;
    }

    @Getter
    public static class ExplicitSkillInvocation {
        private final String skillName;
        private final String arguments;

        public ExplicitSkillInvocation(String skillName, String arguments) {
            this.skillName = skillName;
            this.arguments = arguments;
        }
    }
}
