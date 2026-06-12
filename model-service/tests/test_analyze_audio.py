from fastapi.testclient import TestClient

from app.main import app
from app.providers.openrouter import OpenRouterUnavailable
from app.providers.openrouter_audio import OpenRouterAudioAnalyzeProvider
from app.schemas import AnalyzeAudioRequest


def test_analyze_audio_returns_segments_and_memory_candidate() -> None:
    client = TestClient(app)

    response = client.post(
        "/v1/audio/analyze",
        json={
            "request_id": "req-1",
            "user_id": "user-1",
            "audio_event_id": "audio-1",
            "audio_uri": "local://audio/user-1/sample.wav",
            "started_at": "2026-06-11T09:00:00Z",
            "ended_at": "2026-06-11T09:01:00Z",
            "known_speakers": [],
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["language"] == "zh"
    assert body["segments"][0]["speaker_id"] == 1
    assert body["speaker_embeddings"][0]["quality_score"] == 0.86
    assert body["memory_candidates"][0]["memory_type"] == "life_pattern"


def test_analyze_audio_discards_blank_audio() -> None:
    client = TestClient(app)

    response = client.post(
        "/v1/audio/analyze",
        json={
            "request_id": "req-2",
            "user_id": "user-1",
            "audio_event_id": "audio-2",
            "audio_uri": "local://audio/user-1/blank.wav",
            "started_at": "2026-06-11T09:00:00Z",
            "known_speakers": [],
        },
    )

    assert response.status_code == 200
    assert response.json()["summary"]["discard"] is True


def test_incremental_transcript_returns_deterministic_chunk_text() -> None:
    client = TestClient(app)

    response = client.post(
        "/v1/audio/transcript",
        json={
            "request_id": "req-transcript-1",
            "user_id": "user-1",
            "stream_session_id": "stream-1",
            "chunk_index": 3,
            "chunk_bytes": 2048,
            "is_final": False,
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["stream_session_id"] == "stream-1"
    assert body["sequence"] == 3
    assert body["transcript"] == "实时片段 3: 已接收约 2048 字节音频。"
    assert body["is_final"] is False


def test_openrouter_audio_provider_sends_input_audio_and_marks_speaker_as_self() -> None:
    class FakeChatClient:
        def __init__(self) -> None:
            self.messages = []
            self.reasoning_effort = None
            self.max_tokens = None

        def complete(self, messages, *, temperature=0.35, max_tokens=700, reasoning_effort=None):  # noqa: ANN001
            self.messages = messages
            self.max_tokens = max_tokens
            self.reasoning_effort = reasoning_effort
            return """
            {
              "language": "zh",
              "segments": [
                {
                  "speaker_id": 9,
                  "start_ms": 0,
                  "end_ms": 1800,
                  "transcript": "今天状态不错。",
                  "confidence": 0.88,
                  "emotion_tags": ["calm"],
                  "topic_tags": ["status"]
                }
              ],
              "summary": {
                "title": "状态记录",
                "overview": "用户说今天状态不错。",
                "topic_tags": ["status"],
                "emotion_tags": ["calm"],
                "suggested_actions": [],
                "suggested_events": [],
                "discard": false,
                "discard_reason": null
              },
              "memory_candidates": [],
              "safety": {
                "level": "normal",
                "requires_crisis_response": false,
                "reason": null
              }
            }
            """

    chat_client = FakeChatClient()
    provider = OpenRouterAudioAnalyzeProvider(chat_client=chat_client, mode="chat_audio")

    result = provider.analyze_audio(
        AnalyzeAudioRequest(
            request_id="req-audio",
            user_id="user-1",
            audio_event_id="audio-1",
            audio_uri="local://user-1/audio.webm",
            audio_content_base64="d2ViaQ==",
            audio_format="webm",
            started_at="2026-06-12T10:00:00Z",
            ended_at="2026-06-12T10:00:02Z",
            known_speakers=[],
        )
    )

    content = chat_client.messages[1]["content"]
    assert content[1]["type"] == "input_audio"
    assert content[1]["input_audio"]["data"] == "d2ViaQ=="
    assert chat_client.max_tokens == 4096
    assert chat_client.reasoning_effort == "none"
    assert result.segments[0].speaker_id == 0
    assert result.segments[0].transcript == "今天状态不错。"
    assert result.speaker_embeddings == []


def test_openrouter_audio_provider_uses_dedicated_transcription_by_default() -> None:
    class FakeTranscriptionClient:
        def __init__(self) -> None:
            self.audio_content_base64 = None
            self.audio_format_value = None
            self.language = None

        def transcribe(self, audio_content_base64, audio_format_value, language="zh"):  # noqa: ANN001
            self.audio_content_base64 = audio_content_base64
            self.audio_format_value = audio_format_value
            self.language = language
            return "你好，我今天想聊聊真实的心情。"

    transcription_client = FakeTranscriptionClient()
    provider = OpenRouterAudioAnalyzeProvider(transcription_client=transcription_client)

    result = provider.analyze_audio(
        AnalyzeAudioRequest(
            request_id="req-transcription",
            user_id="user-1",
            audio_event_id="audio-transcription",
            audio_uri="local://user-1/audio.webm",
            audio_content_base64="d2ViaQ==",
            audio_format="webm",
            started_at="2026-06-12T10:00:00Z",
            ended_at="2026-06-12T10:00:02Z",
            known_speakers=[],
        )
    )

    assert transcription_client.audio_content_base64 == "d2ViaQ=="
    assert transcription_client.audio_format_value == "webm"
    assert transcription_client.language == "zh"
    assert result.segments[0].speaker_id == 0
    assert result.segments[0].transcript == "你好，我今天想聊聊真实的心情。"
    assert result.summary.overview == "你好，我今天想聊聊真实的心情。"
    assert result.memory_candidates == []


def test_openrouter_audio_provider_falls_back_when_model_returns_invalid_json() -> None:
    class FakeChatClient:
        def complete(self, messages, *, temperature=0.35, max_tokens=700, reasoning_effort=None):  # noqa: ANN001, ARG002
            return '''
            {
              "language": "zh",
              "segments": [
                {"speaker_id": 0, "transcript": "我今天晚上想做一个 demo。",}
              ]
            '''

    provider = OpenRouterAudioAnalyzeProvider(chat_client=FakeChatClient(), mode="chat_audio")

    result = provider.analyze_audio(
        AnalyzeAudioRequest(
            request_id="req-invalid-json",
            user_id="user-1",
            audio_event_id="audio-invalid-json",
            audio_uri="local://user-1/audio.webm",
            audio_content_base64="d2ViaQ==",
            audio_format="webm",
            started_at="2026-06-12T10:00:00Z",
            ended_at="2026-06-12T10:00:02Z",
            known_speakers=[],
        )
    )

    assert result.segments[0].speaker_id == 0
    assert result.segments[0].transcript == "我今天晚上想做一个 demo。"
    assert result.summary.discard is False
    assert result.summary.title == "语音会话转写"


def test_openrouter_audio_provider_discards_none_like_fallback() -> None:
    class FakeChatClient:
        def complete(self, messages, *, temperature=0.35, max_tokens=700, reasoning_effort=None):  # noqa: ANN001, ARG002
            return "None"

    provider = OpenRouterAudioAnalyzeProvider(chat_client=FakeChatClient(), mode="chat_audio")

    result = provider.analyze_audio(
        AnalyzeAudioRequest(
            request_id="req-none",
            user_id="user-1",
            audio_event_id="audio-none",
            audio_uri="local://user-1/audio.webm",
            audio_content_base64="d2ViaQ==",
            audio_format="webm",
            started_at="2026-06-12T10:00:00Z",
            known_speakers=[],
        )
    )

    assert result.segments == []
    assert result.summary.discard is True


def test_openrouter_audio_provider_accepts_string_memory_candidates_and_actions() -> None:
    class FakeChatClient:
        def complete(self, messages, *, temperature=0.35, max_tokens=700, reasoning_effort=None):  # noqa: ANN001, ARG002
            return """
            {
              "language": "Mandarin Chinese",
              "segments": [
                {
                  "speaker_id": 0,
                  "start_ms": 0,
                  "end_ms": 3000,
                  "transcript": "我今天晚上想做一个 demo。",
                  "confidence": 0.86,
                  "emotion_tags": [],
                  "topic_tags": ["demo"]
                }
              ],
              "summary": {
                "title": "Demo 计划",
                "overview": "用户说今天晚上想做一个 demo。",
                "topic_tags": ["demo"],
                "emotion_tags": [],
                "suggested_actions": ["询问 demo 的主题"],
                "suggested_events": [],
                "discard": false,
                "discard_reason": ""
              },
              "memory_candidates": [
                "用户今天晚上想做一个 demo"
              ],
              "safety": {
                "level": "normal",
                "requires_crisis_response": false,
                "reason": null
              }
            }
            """

    provider = OpenRouterAudioAnalyzeProvider(chat_client=FakeChatClient(), mode="chat_audio")

    result = provider.analyze_audio(
        AnalyzeAudioRequest(
            request_id="req-string-candidates",
            user_id="user-1",
            audio_event_id="audio-string-candidates",
            audio_uri="local://user-1/audio.webm",
            audio_content_base64="d2ViaQ==",
            audio_format="webm",
            started_at="2026-06-12T10:00:00Z",
            known_speakers=[],
        )
    )

    assert result.summary.suggested_actions[0].text == "询问 demo 的主题"
    assert result.memory_candidates[0].content == "用户今天晚上想做一个 demo"
    assert result.memory_candidates[0].memory_type == "conversation_context"


def test_audio_analyze_api_returns_502_when_openrouter_audio_unavailable(monkeypatch) -> None:
    from app import main

    class FailingAudioService:
        def analyze(self, request):  # noqa: ANN001
            raise OpenRouterUnavailable("OpenRouter API key is not configured")

    monkeypatch.setattr(main, "audio_service", FailingAudioService())
    client = TestClient(app)

    response = client.post(
        "/v1/audio/analyze",
        json={
            "request_id": "req-502",
            "user_id": "user-1",
            "audio_event_id": "audio-502",
            "audio_uri": "local://audio/user-1/sample.webm",
            "audio_content_base64": "d2ViaQ==",
            "audio_format": "webm",
            "started_at": "2026-06-12T10:00:00Z",
            "known_speakers": [],
        },
    )

    assert response.status_code == 502
    assert "OpenRouter API key is not configured" in response.json()["detail"]
