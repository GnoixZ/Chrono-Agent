package ai.chrono.backend.task;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class ModelJobService {
    public ModelJobDraft createAudioAnalyzeJob(String userId, UUID audioEventId) {
        String idempotencyKey = "audio_analyze:" + audioEventId;
        return new ModelJobDraft(
                UUID.randomUUID(),
                userId,
                "audio_analyze",
                "audio_event",
                audioEventId,
                "pending",
                0,
                Instant.now(),
                idempotencyKey
        );
    }

    public record ModelJobDraft(
            UUID id,
            String userId,
            String jobType,
            String sourceRefType,
            UUID sourceRefId,
            String status,
            int attempts,
            Instant nextRunAt,
            String idempotencyKey
    ) {
    }
}
