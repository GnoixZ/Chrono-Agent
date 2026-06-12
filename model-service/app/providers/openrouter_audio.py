import json
import re
from typing import Any

from app.providers.openrouter import OpenRouterChatClient, OpenRouterUnavailable
from app.schemas import (
    AnalyzeAudioRequest,
    AnalyzeAudioResponse,
    ConversationSummary,
    MemoryCandidate,
    SafetyResult,
    SpeakerSegment,
    SuggestedAction,
)


class OpenRouterAudioAnalyzeProvider:
    def __init__(self, chat_client: OpenRouterChatClient | None = None) -> None:
        self.chat_client = chat_client or OpenRouterChatClient.from_env()

    def analyze_audio(self, request: AnalyzeAudioRequest) -> AnalyzeAudioResponse:
        if not request.audio_content_base64:
            raise OpenRouterUnavailable("audio_content_base64 is required for OpenRouter audio analysis")

        response = self.chat_client.complete(
            [
                {
                    "role": "system",
                    "content": (
                        "You transcribe personal audio and return strict JSON only. "
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
            max_tokens=1800,
        )
        return parse_audio_analysis(response)

    def incremental_transcript(self, request):  # noqa: ANN001
        raise OpenRouterUnavailable("incremental audio transcript is not enabled for OpenRouter provider")


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
    payload = extract_json_object(value)
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
        suggested_actions=[
            SuggestedAction(type=str(item.get("type", "follow_up")), text=str(item.get("text", "")).strip())
            for item in list_value(summary_payload.get("suggested_actions"))
            if str(item.get("text", "")).strip()
        ],
        suggested_events=list_value(summary_payload.get("suggested_events")),
        discard=discard,
        discard_reason=str(summary_payload.get("discard_reason") or "blank_or_low_value_audio") if discard else None,
    )
    return AnalyzeAudioResponse(
        language=str(payload.get("language") or "zh"),
        segments=segments,
        speaker_embeddings=[],
        summary=summary,
        memory_candidates=[
            MemoryCandidate(
                memory_type=str(item.get("memory_type", "conversation_context")),
                content=str(item.get("content", "")).strip(),
                confidence=float(item.get("confidence", 0.5) or 0.5),
                sensitivity=str(item.get("sensitivity", "normal")),
            )
            for item in list_value(payload.get("memory_candidates"))
            if str(item.get("content", "")).strip()
        ],
        safety=parse_safety(payload.get("safety")),
    )


def extract_json_object(value: str) -> dict[str, Any]:
    text = value.strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?", "", text, flags=re.IGNORECASE).strip()
        text = re.sub(r"```$", "", text).strip()
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


def parse_safety(value: Any) -> SafetyResult:
    payload = dict_value(value)
    return SafetyResult(
        level=str(payload.get("level") or "normal"),
        requires_crisis_response=bool(payload.get("requires_crisis_response", False)),
        reason=None if payload.get("reason") is None else str(payload.get("reason")),
    )


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
