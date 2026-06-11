package ai.chrono.backend.audio;

import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Service
public class AudioStreamService {
    private final Set<String> activeUsers = new HashSet<>();

    public synchronized AudioStreamSessionResponse open(String userId) {
        if (activeUsers.contains(userId)) {
            throw new IllegalStateException("active audio stream already exists");
        }
        activeUsers.add(userId);
        return new AudioStreamSessionResponse(UUID.randomUUID().toString(), "active", null);
    }

    public synchronized AudioStreamSessionResponse close(String userId, String streamSessionId) {
        activeUsers.remove(userId);
        return new AudioStreamSessionResponse(streamSessionId, "closed", null);
    }
}
