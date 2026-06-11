package ai.chrono.backend.safety;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentSafetyGuardTest {
    @Test
    void rejectsTooManyRecallItems() {
        AgentSafetyGuard guard = new AgentSafetyGuard(10, 5);

        assertThatThrownBy(() -> guard.validate(11, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("recall item limit exceeded");
    }
}
