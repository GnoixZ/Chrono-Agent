package ai.chrono.backend.agent;

import jakarta.validation.constraints.NotBlank;

public record AgentMessageRequest(
        @NotBlank String userId,
        @NotBlank String content
) {
}
