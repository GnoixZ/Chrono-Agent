package ai.chrono.backend.health;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record HealthEventRequest(
        @NotBlank String userId,
        @NotBlank String eventType,
        @NotNull Instant measuredAt,
        Double valueNumeric,
        String valueText,
        String unit,
        @NotBlank String source
) {
}
