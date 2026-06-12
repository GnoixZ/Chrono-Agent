package ai.chrono.backend.conversation;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record ConversationSplitRequest(
        @NotBlank String userId,
        List<Part> parts,
        String reason
) {
    public record Part(
            String title,
            String overview
    ) {
    }
}
