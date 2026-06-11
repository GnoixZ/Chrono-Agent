package ai.chrono.backend.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryCandidateDecisionServiceTest {
    @Test
    void requiresConfirmationForLowConfidenceModelCandidate() {
        MemoryCandidateDecisionService service = new MemoryCandidateDecisionService();

        String decision = service.decide("model_suggested", "normal", 0.62);

        assertThat(decision).isEqualTo("needs_user_confirmation");
    }

    @Test
    void autoSavesUserConfirmedMemory() {
        MemoryCandidateDecisionService service = new MemoryCandidateDecisionService();

        String decision = service.decide("user_confirmed", "normal", 0.2);

        assertThat(decision).isEqualTo("auto_saved");
    }
}
