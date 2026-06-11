package ai.chrono.backend.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEvent(
        UUID id,
        String userId,
        String actorType,
        String action,
        String targetType,
        UUID targetId,
        Map<String, Object> metadata,
        Instant createdAt
) {
    public static AuditEvent userAction(String userId, String action, String targetType, UUID targetId) {
        return new AuditEvent(
                UUID.randomUUID(),
                userId,
                "user",
                action,
                targetType,
                targetId,
                Map.of(),
                Instant.now()
        );
    }
}
