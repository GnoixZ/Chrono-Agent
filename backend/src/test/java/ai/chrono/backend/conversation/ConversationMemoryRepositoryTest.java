package ai.chrono.backend.conversation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class ConversationMemoryRepositoryTest {
    @Autowired
    ConversationMemoryRepository repository;

    @Test
    void savesConversationMemoryDraft() {
        ConversationMemory memory = new ConversationMemory(
                UUID.randomUUID(),
                "user-1",
                "audio_recording",
                Instant.parse("2026-06-11T09:00:00Z"),
                "早晨记录",
                "用户进行了一段早晨状态记录"
        );

        ConversationMemory saved = repository.save(memory);

        assertThat(saved.id()).isNotNull();
    }
}
