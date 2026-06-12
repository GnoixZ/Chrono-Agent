from app.schemas import (
    AnalyzeAudioRequest,
    AnalyzeAudioResponse,
    ConversationSummary,
    IncrementalTranscriptRequest,
    IncrementalTranscriptResponse,
    MemoryCandidate,
    SafetyResult,
    SpeakerEmbedding,
    SpeakerSegment,
    SuggestedAction,
)


class FakeModelProvider:
    def incremental_transcript(self, request: IncrementalTranscriptRequest) -> IncrementalTranscriptResponse:
        transcript = (
            f"实时片段 {request.chunk_index}: 已接收约 {request.chunk_bytes} 字节音频。"
            if not request.is_final
            else f"实时片段 {request.chunk_index}: 最后一段音频已接收。"
        )
        return IncrementalTranscriptResponse(
            stream_session_id=request.stream_session_id,
            sequence=request.chunk_index,
            transcript=transcript,
            stability=0.72,
            is_final=request.is_final,
        )

    def analyze_audio(self, request: AnalyzeAudioRequest) -> AnalyzeAudioResponse:
        if "blank" in request.audio_uri:
            return AnalyzeAudioResponse(
                language="zh",
                segments=[],
                speaker_embeddings=[],
                summary=ConversationSummary(
                    title="低价值录音",
                    overview="录音为空白或信息量过低。",
                    discard=True,
                    discard_reason="blank_or_low_value_audio",
                ),
                memory_candidates=[],
                safety=SafetyResult(level="normal"),
            )

        return AnalyzeAudioResponse(
            language="zh",
            segments=[
                SpeakerSegment(
                    speaker_id=1,
                    start_ms=0,
                    end_ms=4200,
                    transcript="我今天有点累，但还是想把事情做完。",
                    confidence=0.91,
                    emotion_tags=["tired"],
                    topic_tags=["work", "energy"],
                )
            ],
            speaker_embeddings=[
                SpeakerEmbedding(
                    speaker_id=1,
                    embedding_ref=f"encrypted://tmp/{request.audio_event_id}/speaker-1",
                    quality_score=0.86,
                )
            ],
            summary=ConversationSummary(
                title="上午状态记录",
                overview="用户提到今天疲惫，但仍想完成工作。",
                topic_tags=["work", "energy"],
                emotion_tags=["tired"],
                suggested_actions=[
                    SuggestedAction(type="self_care", text="今天安排一个短休息窗口。")
                ],
                discard=False,
            ),
            memory_candidates=[
                MemoryCandidate(
                    memory_type="life_pattern",
                    content="用户在工作日上午容易感到疲惫。",
                    confidence=0.62,
                    sensitivity="normal",
                )
            ],
            safety=SafetyResult(level="normal"),
        )
