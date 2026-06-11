package ai.chrono.backend.audio;

public record AudioStreamSessionResponse(
        String streamSessionId,
        String status,
        String currentAudioEventId
) {
}
