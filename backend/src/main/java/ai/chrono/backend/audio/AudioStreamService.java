package ai.chrono.backend.audio;

import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AudioStreamService {
    private final Map<String, String> activeStreamsByUser = new HashMap<>();

    public synchronized AudioStreamSessionResponse open(String userId) {
        String streamSessionId = UUID.randomUUID().toString();
        activeStreamsByUser.put(userId, streamSessionId);
        return new AudioStreamSessionResponse(streamSessionId, "active", null);
    }

    public synchronized AudioStreamSessionResponse close(String userId, String streamSessionId) {
        activeStreamsByUser.remove(userId, streamSessionId);
        return new AudioStreamSessionResponse(streamSessionId, "closed", null);
    }
}
