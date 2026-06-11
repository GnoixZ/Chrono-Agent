package ai.chrono.backend.health;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HealthEventRequestTest {
    @Test
    void acceptsMvpHealthEventTypes() {
        assertThat(HealthEventTypes.isAllowed("heart_rate")).isTrue();
        assertThat(HealthEventTypes.isAllowed("mood_check_in")).isTrue();
    }

    @Test
    void rejectsUnsupportedHealthEventType() {
        assertThat(HealthEventTypes.isAllowed("blood_test_report")).isFalse();
    }
}
