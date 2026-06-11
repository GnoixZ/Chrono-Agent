package ai.chrono.backend.demo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record DemoHealthRequest(
        @NotBlank String userId,
        @NotBlank String eventType,
        @NotNull Instant measuredAt,
        Double valueNumeric,
        String valueText,
        String unit,
        String source
) {
}
