package ai.chrono.backend.memory;

import java.util.List;

public record AgentContext(
        String userId,
        List<ContextItem> items
) {
    public record ContextItem(
            String sourceType,
            String sourceId,
            String content,
            String reason,
            double score
    ) {
    }
}
