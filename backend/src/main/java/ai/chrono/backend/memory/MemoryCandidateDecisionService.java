package ai.chrono.backend.memory;

import org.springframework.stereotype.Service;

@Service
public class MemoryCandidateDecisionService {
    public String decide(String sourceType, String sensitivity, double confidence) {
        if ("user_confirmed".equals(sourceType)) {
            return "auto_saved";
        }
        if (!"normal".equals(sensitivity)) {
            return "needs_user_confirmation";
        }
        if (confidence >= 0.75) {
            return "auto_saved";
        }
        return "needs_user_confirmation";
    }
}
