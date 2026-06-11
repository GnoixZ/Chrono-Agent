package ai.chrono.backend.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ConversationMemoryRepository extends JpaRepository<ConversationMemory, UUID> {
}
