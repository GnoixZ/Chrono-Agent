package ai.chrono.backend.modelclient.dto;

import com.alibaba.fastjson2.annotation.JSONField;

public record VectorDocumentDto(
        @JSONField(name = "document_id") String documentId,
        @JSONField(name = "user_id") String userId,
        @JSONField(name = "source_type") String sourceType,
        @JSONField(name = "source_id") String sourceId,
        String content,
        String reason,
        @JSONField(name = "created_at") String createdAt
) {
}
