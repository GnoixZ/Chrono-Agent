package ai.chrono.backend.modelclient.dto;

import com.alibaba.fastjson2.annotation.JSONField;

public record VectorSearchRequest(
        @JSONField(name = "request_id") String requestId,
        @JSONField(name = "user_id") String userId,
        String query,
        @JSONField(name = "top_k") Integer topK
) {
}
