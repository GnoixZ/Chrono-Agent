package ai.chrono.backend.audio;

public record AudioUploadResponse(
        String audioEventId,
        String processingStatus,
        String conversationMemoryId
) {
}
