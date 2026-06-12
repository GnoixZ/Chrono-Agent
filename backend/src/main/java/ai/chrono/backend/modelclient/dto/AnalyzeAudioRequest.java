package ai.chrono.backend.modelclient.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import java.util.List;

public record AnalyzeAudioRequest(
        @JSONField(name = "request_id") String requestId,
        @JSONField(name = "user_id") String userId,
        @JSONField(name = "audio_event_id") String audioEventId,
        @JSONField(name = "audio_uri") String audioUri,
        @JSONField(name = "audio_content_base64") String audioContentBase64,
        @JSONField(name = "audio_format") String audioFormat,
        @JSONField(name = "started_at") String startedAt,
        @JSONField(name = "ended_at") String endedAt,
        @JSONField(name = "known_speakers") List<KnownSpeakerDto> knownSpeakers
) {
    public record KnownSpeakerDto(
            @JSONField(name = "speaker_cluster_id") String speakerClusterId,
            @JSONField(name = "display_name") String displayName,
            @JSONField(name = "embedding_refs") List<String> embeddingRefs
    ) {
    }
}
