package ai.chrono.backend.demo;

import jakarta.validation.constraints.NotBlank;

public record SpeakerLabelRequest(
        @NotBlank String userId,
        @NotBlank String displayName
) {
}
