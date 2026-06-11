package ai.chrono.backend.demo;

import java.util.List;
import java.util.Map;

public record DemoAgentMessageResponse(
        String conversationSessionId,
        String userMessageId,
        String assistantMessageId,
        String runId,
        String content,
        String safetyLevel,
        List<Map<String, Object>> recalledContext,
        List<Map<String, Object>> memoryCandidates
) {
}
