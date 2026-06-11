package ai.chrono.backend.demo;

import java.util.List;
import java.util.Map;

public record DemoStateResponse(
        String userId,
        List<Map<String, Object>> audioEvents,
        List<Map<String, Object>> audioStreamSessions,
        List<Map<String, Object>> healthEvents,
        List<Map<String, Object>> conversationMemories,
        List<Map<String, Object>> speakerClusters,
        List<Map<String, Object>> speakerSegments,
        List<Map<String, Object>> personInsights,
        List<Map<String, Object>> memoryCandidates,
        List<Map<String, Object>> memoryItems,
        List<Map<String, Object>> agentSessions,
        List<Map<String, Object>> agentMessages,
        List<Map<String, Object>> agentRuns,
        List<Map<String, Object>> recallEvents,
        List<Map<String, Object>> modelJobs,
        List<Map<String, Object>> auditLogs
) {
}
