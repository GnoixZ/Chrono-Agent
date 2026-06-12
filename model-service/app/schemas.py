from pydantic import BaseModel, Field


class KnownSpeaker(BaseModel):
    speaker_cluster_id: str
    display_name: str
    embedding_refs: list[str] = Field(default_factory=list)


class AnalyzeAudioRequest(BaseModel):
    request_id: str
    user_id: str
    audio_event_id: str
    audio_uri: str
    audio_content_base64: str | None = None
    audio_format: str | None = None
    started_at: str
    ended_at: str | None = None
    known_speakers: list[KnownSpeaker] = Field(default_factory=list)


class SpeakerSegment(BaseModel):
    speaker_id: int
    start_ms: int
    end_ms: int
    transcript: str
    confidence: float
    emotion_tags: list[str] = Field(default_factory=list)
    topic_tags: list[str] = Field(default_factory=list)


class SpeakerEmbedding(BaseModel):
    speaker_id: int
    embedding_ref: str
    quality_score: float


class SuggestedAction(BaseModel):
    type: str
    text: str


class ConversationSummary(BaseModel):
    title: str
    overview: str
    topic_tags: list[str] = Field(default_factory=list)
    emotion_tags: list[str] = Field(default_factory=list)
    suggested_actions: list[SuggestedAction] = Field(default_factory=list)
    suggested_events: list[dict] = Field(default_factory=list)
    discard: bool = False
    discard_reason: str | None = None


class MemoryCandidate(BaseModel):
    memory_type: str
    content: str
    confidence: float
    sensitivity: str = "normal"


class SafetyResult(BaseModel):
    level: str
    requires_crisis_response: bool = False
    reason: str | None = None


class AnalyzeAudioResponse(BaseModel):
    language: str
    segments: list[SpeakerSegment] = Field(default_factory=list)
    speaker_embeddings: list[SpeakerEmbedding] = Field(default_factory=list)
    summary: ConversationSummary
    memory_candidates: list[MemoryCandidate] = Field(default_factory=list)
    safety: SafetyResult


class IncrementalTranscriptRequest(BaseModel):
    request_id: str
    user_id: str
    stream_session_id: str
    chunk_index: int
    chunk_bytes: int
    is_final: bool = False


class IncrementalTranscriptResponse(BaseModel):
    stream_session_id: str
    sequence: int
    transcript: str
    stability: float
    is_final: bool = False


class AgentContextItem(BaseModel):
    source_type: str
    source_id: str
    content: str
    reason: str
    score: float


class AgentReplyRequest(BaseModel):
    request_id: str
    user_id: str
    conversation_session_id: str
    message_id: str
    user_message: str
    context_items: list[AgentContextItem] = Field(default_factory=list)


class AgentReplyResponse(BaseModel):
    content: str
    safety: SafetyResult
    memory_candidates: list[MemoryCandidate] = Field(default_factory=list)


class VectorDocument(BaseModel):
    document_id: str
    user_id: str
    source_type: str
    source_id: str
    content: str
    reason: str = ""
    created_at: str | None = None


class VectorUpsertRequest(BaseModel):
    request_id: str
    documents: list[VectorDocument] = Field(default_factory=list)


class VectorUpsertResponse(BaseModel):
    indexed_count: int


class VectorSearchRequest(BaseModel):
    request_id: str
    user_id: str
    query: str
    top_k: int = 8


class VectorSearchResult(BaseModel):
    document_id: str
    source_type: str
    source_id: str
    content: str
    reason: str
    score: float


class VectorSearchResponse(BaseModel):
    items: list[VectorSearchResult] = Field(default_factory=list)
