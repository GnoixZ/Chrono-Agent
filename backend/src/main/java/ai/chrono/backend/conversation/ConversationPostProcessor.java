package ai.chrono.backend.conversation;

import ai.chrono.backend.modelclient.dto.AnalyzeAudioResponse;
import org.springframework.stereotype.Service;

@Service
public class ConversationPostProcessor {
    public PostProcessingDecision decide(AnalyzeAudioResponse response) {
        if (Boolean.TRUE.equals(response.summary().discard())) {
            return new PostProcessingDecision("discarded", response.summary().discardReason());
        }
        return new PostProcessingDecision("completed", null);
    }

    public record PostProcessingDecision(String status, String reason) {
    }
}
