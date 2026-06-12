from fastapi.testclient import TestClient

from app.main import app


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
