package ai.chrono.backend.audio;

import ai.chrono.backend.task.ModelJobService;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AudioService {
    private final AudioStorage audioStorage;
    private final ModelJobService modelJobService;

    public AudioService(AudioStorage audioStorage, ModelJobService modelJobService) {
        this.audioStorage = audioStorage;
        this.modelJobService = modelJobService;
    }

    public AudioUploadResponse upload(String userId, String fileName, byte[] bytes) {
        audioStorage.save(new AudioStorage.AudioInput(userId, fileName, bytes));
        UUID audioEventId = UUID.randomUUID();
        modelJobService.createAudioAnalyzeJob(userId, audioEventId);
        return new AudioUploadResponse(audioEventId.toString(), "pending", null);
    }
}
