package ai.chrono.backend.conversation;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;

@Entity
public class ConversationMemory {
    @Id
    private UUID id;
    private String userId;
    private String sourceType;
    private Instant startedAt;
    private Instant endedAt;
    private String title;
    private String overview;
    private String language;
    private String category;
    private String status;
    private String postProcessingStatus;
    private Integer processingAttempts;
    private Boolean discarded;
    private String discardReason;
    private String visibility;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;

    protected ConversationMemory() {
    }

    public ConversationMemory(UUID id, String userId, String sourceType, Instant startedAt, String title, String overview) {
        this.id = id;
        this.userId = userId;
        this.sourceType = sourceType;
        this.startedAt = startedAt;
        this.title = title;
        this.overview = overview;
        this.status = "in_progress";
        this.postProcessingStatus = "not_started";
        this.processingAttempts = 0;
        this.discarded = false;
        this.visibility = "private";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public UUID id() {
        return id;
    }
}
