package ai.chrono.backend.memory;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MemoryService {
    public AgentContext buildContext(String userId, String userMessage) {
        AgentContext.ContextItem currentMessage = new AgentContext.ContextItem(
                "current_message",
                "inline",
                userMessage,
                "当前用户消息必须进入短期上下文",
                1.0
        );
        return new AgentContext(userId, List.of(currentMessage));
    }
}
