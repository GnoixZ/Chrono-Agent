package ai.chrono.backend.demo;

import jakarta.validation.constraints.NotBlank;

public record DemoAgentMessageRequest(
        @NotBlank String userId,
        String conversationSessionId,
        @NotBlank String content
) {
}
