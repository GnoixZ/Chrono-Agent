package ai.chrono.backend.modelclient.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import java.util.List;

public record AgentReplyRequest(
        @JSONField(name = "request_id") String requestId,
        @JSONField(name = "user_id") String userId,
        @JSONField(name = "conversation_session_id") String conversationSessionId,
        @JSONField(name = "message_id") String messageId,
        @JSONField(name = "user_message") String userMessage,
        @JSONField(name = "context_items") List<AgentContextItemDto> contextItems
) {
    public record AgentContextItemDto(
            @JSONField(name = "source_type") String sourceType,
            @JSONField(name = "source_id") String sourceId,
            String content,
            String reason,
            Double score
    ) {
    }
}
