package ai.chrono.backend.modelclient.dto;

import com.alibaba.fastjson2.annotation.JSONField;

public record IncrementalTranscriptRequest(
        @JSONField(name = "request_id") String requestId,
        @JSONField(name = "user_id") String userId,
        @JSONField(name = "stream_session_id") String streamSessionId,
        @JSONField(name = "chunk_index") Integer chunkIndex,
        @JSONField(name = "chunk_bytes") Integer chunkBytes,
        @JSONField(name = "is_final") Boolean isFinal
) {
}
