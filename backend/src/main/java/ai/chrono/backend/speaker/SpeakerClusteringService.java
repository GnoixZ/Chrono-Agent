package ai.chrono.backend.speaker;

import org.springframework.stereotype.Service;

@Service
public class SpeakerClusteringService {
    private static final double HIGH_THRESHOLD = 0.82;
    private static final double LOW_THRESHOLD = 0.70;

    public String decide(double similarity) {
        if (similarity >= HIGH_THRESHOLD) {
            return "match_existing";
        }
        if (similarity >= LOW_THRESHOLD) {
            return "needs_user_confirmation";
        }
        return "create_unknown_cluster";
    }
}
