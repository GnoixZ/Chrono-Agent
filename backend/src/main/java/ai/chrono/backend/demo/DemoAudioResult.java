package ai.chrono.backend.demo;

import java.util.List;
import java.util.Map;

public record DemoAudioResult(
        String audioEventId,
        String modelJobId,
        String conversationMemoryId,
        String processingStatus,
        String title,
        String overview,
        List<Map<String, Object>> segments,
        List<Map<String, Object>> speakers,
        List<Map<String, Object>> memoryCandidates
) {
}
