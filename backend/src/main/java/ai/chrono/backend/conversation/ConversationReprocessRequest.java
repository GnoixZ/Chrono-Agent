package ai.chrono.backend.conversation;

import jakarta.validation.constraints.NotBlank;

public record ConversationReprocessRequest(
        @NotBlank String userId,
        String reason
) {
}
