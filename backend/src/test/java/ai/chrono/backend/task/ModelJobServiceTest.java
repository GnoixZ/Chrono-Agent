package ai.chrono.backend.task;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ModelJobServiceTest {
    @Test
    void createsAudioAnalyzeJobWithStableIdempotencyKey() {
        UUID audioEventId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        ModelJobService service = new ModelJobService();

        ModelJobService.ModelJobDraft draft = service.createAudioAnalyzeJob("user-1", audioEventId);

        assertThat(draft.jobType()).isEqualTo("audio_analyze");
        assertThat(draft.sourceRefType()).isEqualTo("audio_event");
        assertThat(draft.idempotencyKey()).isEqualTo("audio_analyze:11111111-1111-1111-1111-111111111111");
    }
}
