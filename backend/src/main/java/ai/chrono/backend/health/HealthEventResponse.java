package ai.chrono.backend.health;

public record HealthEventResponse(
        String id,
        String userId,
        String eventType,
        String measuredAt,
        Double valueNumeric,
        String valueText,
        String unit,
        String source,
        String displayValue,
        String createdAt
) {
}
