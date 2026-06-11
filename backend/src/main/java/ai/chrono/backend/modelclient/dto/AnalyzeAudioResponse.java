package ai.chrono.backend.modelclient.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import java.util.List;
import java.util.Map;

public record AnalyzeAudioResponse(
        String language,
        List<SpeakerSegmentDto> segments,
        @JSONField(name = "speaker_embeddings") List<SpeakerEmbeddingDto> speakerEmbeddings,
        ConversationSummaryDto summary,
        @JSONField(name = "memory_candidates") List<MemoryCandidateDto> memoryCandidates,
        SafetyResultDto safety
) {
    public record SpeakerSegmentDto(
            @JSONField(name = "speaker_id") Integer speakerId,
            @JSONField(name = "start_ms") Integer startMs,
            @JSONField(name = "end_ms") Integer endMs,
            String transcript,
            Double confidence,
            @JSONField(name = "emotion_tags") List<String> emotionTags,
            @JSONField(name = "topic_tags") List<String> topicTags
    ) {
    }

    public record SpeakerEmbeddingDto(
            @JSONField(name = "speaker_id") Integer speakerId,
            @JSONField(name = "embedding_ref") String embeddingRef,
            @JSONField(name = "quality_score") Double qualityScore
    ) {
    }

    public record SuggestedActionDto(
            String type,
            String text
    ) {
    }

    public record ConversationSummaryDto(
            String title,
            String overview,
            @JSONField(name = "topic_tags") List<String> topicTags,
            @JSONField(name = "emotion_tags") List<String> emotionTags,
            @JSONField(name = "suggested_actions") List<SuggestedActionDto> suggestedActions,
            @JSONField(name = "suggested_events") List<Map<String, Object>> suggestedEvents,
            Boolean discard,
            @JSONField(name = "discard_reason") String discardReason
    ) {
    }

    public record MemoryCandidateDto(
            @JSONField(name = "memory_type") String memoryType,
            String content,
            Double confidence,
            String sensitivity
    ) {
    }

    public record SafetyResultDto(
            String level,
            @JSONField(name = "requires_crisis_response") Boolean requiresCrisisResponse,
            String reason
    ) {
    }
}
