package ai.chrono.backend.health;

public record HealthEventResponse(
        String id,
        String userId,
        String eventType,
        String measuredAt,
        String displayValue
) {
}
