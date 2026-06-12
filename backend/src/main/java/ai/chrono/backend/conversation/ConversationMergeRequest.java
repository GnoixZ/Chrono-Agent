package ai.chrono.backend.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ConversationMergeRequest(
        @NotBlank String userId,
        @NotEmpty List<String> conversationMemoryIds,
        String reason
) {
}
