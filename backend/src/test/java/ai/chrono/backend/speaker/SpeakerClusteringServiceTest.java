package ai.chrono.backend.speaker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpeakerClusteringServiceTest {
    @Test
    void matchesExistingClusterWhenSimilarityIsHigh() {
        SpeakerClusteringService service = new SpeakerClusteringService();

        assertThat(service.decide(0.9)).isEqualTo("match_existing");
    }

    @Test
    void createsUnknownClusterWhenSimilarityIsLow() {
        SpeakerClusteringService service = new SpeakerClusteringService();

        assertThat(service.decide(0.4)).isEqualTo("create_unknown_cluster");
    }
}
