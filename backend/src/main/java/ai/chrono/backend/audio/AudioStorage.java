package ai.chrono.backend.audio;

import java.util.Optional;

public interface AudioStorage {
    StoredAudio save(AudioInput input);

    Optional<StoredAudio> find(String audioUri);

    void delete(String audioUri);

    record AudioInput(String userId, String fileName, byte[] bytes) {
    }

    record StoredAudio(String audioUri, long sizeBytes) {
    }
}
