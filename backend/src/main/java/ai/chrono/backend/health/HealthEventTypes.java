package ai.chrono.backend.health;

import java.util.Set;

public final class HealthEventTypes {
    private static final Set<String> ALLOWED = Set.of(
            "heart_rate",
            "sleep_duration",
            "steps",
            "activity_minutes",
            "stress_score",
            "mood_check_in"
    );

    private HealthEventTypes() {
    }

    public static boolean isAllowed(String eventType) {
        return ALLOWED.contains(eventType);
    }
}
