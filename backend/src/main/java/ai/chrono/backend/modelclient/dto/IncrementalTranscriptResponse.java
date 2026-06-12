package ai.chrono.backend.modelclient.dto;

import com.alibaba.fastjson2.annotation.JSONField;

public record IncrementalTranscriptResponse(
        @JSONField(name = "stream_session_id") String streamSessionId,
        Integer sequence,
        String transcript,
        Double stability,
        @JSONField(name = "is_final") Boolean isFinal
) {
}
