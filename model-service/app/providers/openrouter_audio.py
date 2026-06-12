import json
import os
import re
import socket
from typing import Any
import urllib.error
import urllib.request

from app.providers.openrouter import DEFAULT_BASE_URL, OpenRouterChatClient, OpenRouterUnavailable, trim
from app.schemas import (
    AnalyzeAudioRequest,
    AnalyzeAudioResponse,
    ConversationSummary,
    MemoryCandidate,
    SafetyResult,
    SpeakerSegment,
    SuggestedAction,
)


DEFAULT_TRANSCRIPTION_MODEL = "qwen/qwen3-asr-flash-2026-02-10"


class OpenRouterAudioAnalyzeProvider:
    def __init__(
        self,
        chat_client: OpenRouterChatClient | None = None,
        transcription_client: "OpenRouterTranscriptionClient | None" = None,
        mode: str | None = None,
    ) -> None:
        self.chat_client = chat_client
        self.transcription_client = transcription_client
        self.mode = (mode or os.getenv("CHRONO_OPENROUTER_AUDIO_ANALYZE_MODE", "transcription")).strip().lower()

    def analyze_audio(self, request: AnalyzeAudioRequest) -> AnalyzeAudioResponse:
        if not request.audio_content_base64:
            raise OpenRouterUnavailable("audio_content_base64 is required for OpenRouter audio analysis")
        if self.mode in {"asr", "transcription"}:
            transcription_client = self.transcription_client or OpenRouterTranscriptionClient.from_env()
            transcript = transcription_client.transcribe(
                request.audio_content_base64,
                audio_format(request.audio_format),
                language=os.getenv("CHRONO_OPENROUTER_TRANSCRIPTION_LANGUAGE", "zh"),
            )
            return response_from_transcript(transcript)

        chat_client = self.chat_client or OpenRouterChatClient.from_env()
        response = chat_client.complete(
            [
                {
                    "role": "system",
                    "content": (
                        "You transcribe personal audio and return strict JSON only. "
                        "When the spoken language is Mandarin Chinese, write Simplified Chinese characters instead of pinyin. "
                        "Do not infer identity, age, gender, job, relationship, medical diagnosis, or protected traits. "
                        "For now, treat every spoken segment as the user themself."
                    ),
                },
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "text",
                            "text": (
                                "Transcribe this audio. Return only JSON with keys: "
                                "language, segments, summary, memory_candidates, safety. "
                                "segments must contain objects with speaker_id, start_ms, end_ms, transcript, confidence, "
                                "emotion_tags, topic_tags. Use speaker_id 0 for every segment. "
                                "summary must contain title, overview, topic_tags, emotion_tags, suggested_actions, "
                                "suggested_events, discard, discard_reason. "
                                "memory_candidates should include only stable user facts, preferences, plans, or useful context "
                                "that can help an assistant answer later questions. "
                                "safety must contain level, requires_crisis_response, reason. "
                                "If speech is blank, return an empty segments array and summary.discard=true."
                            ),
                        },
                        {
                            "type": "input_audio",
                            "input_audio": {
                                "data": request.audio_content_base64,
                                "format": audio_format(request.audio_format),
                            },
                        },
                    ],
                },
            ],
            temperature=0.1,
            max_tokens=4096,
            reasoning_effort="none",
        )
        return parse_audio_analysis(response)

    def incremental_transcript(self, request):  # noqa: ANN001
        raise OpenRouterUnavailable("incremental audio transcript is not enabled for OpenRouter provider")


class OpenRouterTranscriptionClient:
    def __init__(
        self,
        api_key: str | None,
        model: str = DEFAULT_TRANSCRIPTION_MODEL,
        base_url: str = DEFAULT_BASE_URL,
        timeout_seconds: float = 60.0,
    ) -> None:
        self.api_key = api_key
        self.model = model
        self.base_url = base_url.rstrip("/")
        self.timeout_seconds = timeout_seconds

    @classmethod
    def from_env(cls) -> "OpenRouterTranscriptionClient":
        return cls(
            api_key=os.getenv("CHRONO_OPENROUTER_API_KEY") or os.getenv("OPENROUTER_API_KEY"),
            model=os.getenv("CHRONO_OPENROUTER_TRANSCRIPTION_MODEL", DEFAULT_TRANSCRIPTION_MODEL),
            base_url=os.getenv("CHRONO_OPENROUTER_BASE_URL", DEFAULT_BASE_URL).rstrip("/"),
            timeout_seconds=float(os.getenv("CHRONO_OPENROUTER_TRANSCRIPTION_TIMEOUT_SECONDS", "60")),
        )

    def transcribe(self, audio_content_base64: str, audio_format_value: str, language: str | None = "zh") -> str:
        if not self.api_key:
            raise OpenRouterUnavailable("OpenRouter API key is not configured")
        payload: dict[str, Any] = {
            "model": self.model,
            "input_audio": {
                "data": audio_content_base64,
                "format": audio_format_value,
            },
            "temperature": 0,
        }
        if language:
            payload["language"] = language
        request = urllib.request.Request(
            f"{self.base_url}/audio/transcriptions",
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
                "X-Title": "Chrono Agent",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                response_body = response.read().decode("utf-8")
        except urllib.error.HTTPError as error:
            body = error.read().decode("utf-8", errors="replace")
            raise OpenRouterUnavailable(f"OpenRouter audio transcription returned HTTP {error.code}: {trim(body)}") from error
        except (urllib.error.URLError, TimeoutError, socket.timeout, OSError) as error:
            raise OpenRouterUnavailable(f"OpenRouter audio transcription request failed: {error}") from error
        try:
            data = json.loads(response_body)
            text = str(data.get("text", "")).strip()
        except json.JSONDecodeError as error:
            raise OpenRouterUnavailable("OpenRouter audio transcription response was not JSON") from error
        if not text:
            raise OpenRouterUnavailable("OpenRouter audio transcription returned empty text")
        return text


def audio_format(value: str | None) -> str:
    normalized = (value or "webm").lower().strip().lstrip(".")
    return {
        "webm": "webm",
        "wav": "wav",
        "mp3": "mp3",
        "m4a": "mp4",
        "mp4": "mp4",
        "ogg": "ogg",
    }.get(normalized, "webm")


def parse_audio_analysis(value: str) -> AnalyzeAudioResponse:
    try:
        payload = extract_json_object(value)
    except OpenRouterUnavailable:
        return fallback_audio_analysis(value)
    return response_from_payload(payload)


def response_from_payload(payload: dict[str, Any]) -> AnalyzeAudioResponse:
    segments = [
        SpeakerSegment(
            speaker_id=0,
            start_ms=int(item.get("start_ms", 0) or 0),
            end_ms=max(int(item.get("end_ms", 0) or 0), int(item.get("start_ms", 0) or 0)),
            transcript=str(item.get("transcript", "")).strip(),
            confidence=float(item.get("confidence", 0.75) or 0.75),
            emotion_tags=string_list(item.get("emotion_tags")),
            topic_tags=string_list(item.get("topic_tags")),
        )
        for item in list_value(payload.get("segments"))
        if str(item.get("transcript", "")).strip()
    ]
    summary_payload = dict_value(payload.get("summary"))
    transcript = " ".join(segment.transcript for segment in segments).strip()
    discard = bool(summary_payload.get("discard", False)) or not transcript
    summary = ConversationSummary(
        title=str(summary_payload.get("title") or ("低价值录音" if discard else "语音会话转写")),
        overview=str(summary_payload.get("overview") or ("本次音频没有形成可用转写。" if discard else transcript[:180])),
        topic_tags=string_list(summary_payload.get("topic_tags")),
        emotion_tags=string_list(summary_payload.get("emotion_tags")),
        suggested_actions=parse_suggested_actions(summary_payload.get("suggested_actions")),
        suggested_events=list_value(summary_payload.get("suggested_events")),
        discard=discard,
        discard_reason=str(summary_payload.get("discard_reason") or "blank_or_low_value_audio") if discard else None,
    )
    return AnalyzeAudioResponse(
        language=str(payload.get("language") or "zh"),
        segments=segments,
        speaker_embeddings=[],
        summary=summary,
        memory_candidates=parse_memory_candidates(payload.get("memory_candidates")),
        safety=parse_safety(payload.get("safety")),
    )


def response_from_transcript(transcript: str) -> AnalyzeAudioResponse:
    normalized = normalize_text(transcript)
    discard = not normalized
    return AnalyzeAudioResponse(
        language="zh",
        segments=[
            SpeakerSegment(
                speaker_id=0,
                start_ms=0,
                end_ms=0,
                transcript=normalized,
                confidence=0.9,
                emotion_tags=[],
                topic_tags=["audio_transcript"],
            )
        ] if normalized else [],
        speaker_embeddings=[],
        summary=ConversationSummary(
            title="语音会话转写" if normalized else "低价值录音",
            overview=normalized[:180] if normalized else "本次音频没有形成可用转写。",
            topic_tags=["audio_transcript"] if normalized else [],
            emotion_tags=[],
            suggested_actions=[],
            suggested_events=[],
            discard=discard,
            discard_reason="blank_or_low_value_audio" if discard else None,
        ),
        memory_candidates=[],
        safety=SafetyResult(level="normal", requires_crisis_response=False, reason=None),
    )


def fallback_audio_analysis(value: str) -> AnalyzeAudioResponse:
    transcript = fallback_transcript(value)
    discard = not transcript
    segments = []
    if transcript:
        segments.append(SpeakerSegment(
            speaker_id=0,
            start_ms=0,
            end_ms=0,
            transcript=transcript,
            confidence=0.5,
            emotion_tags=[],
            topic_tags=["audio_transcript"],
        ))
    return AnalyzeAudioResponse(
        language="zh",
        segments=segments,
        speaker_embeddings=[],
        summary=ConversationSummary(
            title="语音会话转写" if transcript else "低价值录音",
            overview=transcript[:180] if transcript else "本次音频没有形成可用转写。",
            topic_tags=["audio_transcript"] if transcript else [],
            emotion_tags=[],
            suggested_actions=[],
            suggested_events=[],
            discard=discard,
            discard_reason="blank_or_low_value_audio" if discard else None,
        ),
        memory_candidates=[],
        safety=SafetyResult(level="normal", requires_crisis_response=False, reason=None),
    )


def fallback_transcript(value: str) -> str:
    text = strip_code_fence(value)
    if text.lower() in {"none", "null"}:
        return ""
    snippets = []
    for match in re.finditer(r'"transcript"\s*:\s*"((?:\\.|[^"\\])*)"', text, flags=re.IGNORECASE):
        try:
            snippets.append(json.loads(f'"{match.group(1)}"'))
        except json.JSONDecodeError:
            snippets.append(match.group(1))
    if snippets:
        return normalize_text(" ".join(snippets))
    text = re.sub(r"^\s*(here is|以下是|下面是).*?:", "", text, flags=re.IGNORECASE | re.DOTALL)
    return normalize_text(text)


def extract_json_object(value: str) -> dict[str, Any]:
    text = strip_code_fence(value)
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", text, flags=re.DOTALL)
        if not match:
            raise OpenRouterUnavailable("OpenRouter audio analysis did not return JSON")
        try:
            parsed = json.loads(match.group(0))
        except json.JSONDecodeError as error:
            raise OpenRouterUnavailable("OpenRouter audio analysis returned invalid JSON") from error
    if not isinstance(parsed, dict):
        raise OpenRouterUnavailable("OpenRouter audio analysis JSON must be an object")
    return parsed


def strip_code_fence(value: str) -> str:
    text = value.strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?", "", text, flags=re.IGNORECASE).strip()
        text = re.sub(r"```$", "", text).strip()
    return text


def normalize_text(value: str) -> str:
    return " ".join(value.strip().split())


def parse_safety(value: Any) -> SafetyResult:
    payload = dict_value(value)
    return SafetyResult(
        level=str(payload.get("level") or "normal"),
        requires_crisis_response=bool(payload.get("requires_crisis_response", False)),
        reason=None if payload.get("reason") is None else str(payload.get("reason")),
    )


def parse_suggested_actions(value: Any) -> list[SuggestedAction]:
    if not isinstance(value, list):
        return []
    actions = []
    for item in value:
        if isinstance(item, dict):
            text = str(item.get("text", "")).strip()
            if text:
                actions.append(SuggestedAction(type=str(item.get("type", "follow_up")), text=text))
            continue
        text = str(item).strip()
        if text:
            actions.append(SuggestedAction(type="follow_up", text=text))
    return actions


def parse_memory_candidates(value: Any) -> list[MemoryCandidate]:
    if not isinstance(value, list):
        return []
    candidates = []
    for item in value:
        if isinstance(item, dict):
            content = str(item.get("content", "")).strip()
            if content:
                candidates.append(MemoryCandidate(
                    memory_type=str(item.get("memory_type", "conversation_context")),
                    content=content,
                    confidence=float(item.get("confidence", 0.5) or 0.5),
                    sensitivity=str(item.get("sensitivity", "normal")),
                ))
            continue
        content = str(item).strip()
        if content:
            candidates.append(MemoryCandidate(
                memory_type="conversation_context",
                content=content,
                confidence=0.55,
                sensitivity="normal",
            ))
    return candidates


def dict_value(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def list_value(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    return [item for item in value if isinstance(item, dict)]


def string_list(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(item) for item in value if str(item).strip()]
