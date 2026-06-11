package ai.chrono.backend.agent;

public record AgentMessageResponse(
        String conversationSessionId,
        String userMessageId,
        String assistantMessageId,
        String runId,
        String content,
        String safetyLevel
) {
}
