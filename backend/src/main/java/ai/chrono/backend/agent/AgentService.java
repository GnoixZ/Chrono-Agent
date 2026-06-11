package ai.chrono.backend.agent;

import ai.chrono.backend.memory.AgentContext;
import ai.chrono.backend.memory.MemoryService;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AgentService {
    private final MemoryService memoryService;

    public AgentService(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    public AgentMessageResponse reply(AgentMessageRequest request) {
        AgentContext context = memoryService.buildContext(request.userId(), request.content());
        String assistantText = "我会先帮你做一个温和复盘：你刚才提到的内容可以和最近睡眠、压力和互动情况一起看。";
        return new AgentMessageResponse(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                assistantText,
                context.items().isEmpty() ? "normal" : "normal"
        );
    }
}
