package ai.chrono.backend.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryServiceTest {
    @Test
    void includesCurrentMessageInShortTermContext() {
        MemoryService service = new MemoryService();

        AgentContext context = service.buildContext("user-1", "我今天有点累");

        assertThat(context.userId()).isEqualTo("user-1");
        assertThat(context.items()).hasSize(1);
        assertThat(context.items().get(0).sourceType()).isEqualTo("current_message");
    }
}
