from fastapi.testclient import TestClient

from app.main import app


def test_generate_reply_returns_safe_supportive_text() -> None:
    client = TestClient(app)

    response = client.post(
        "/v1/agent/reply",
        json={
            "request_id": "req-1",
            "user_id": "user-1",
            "conversation_session_id": "session-1",
            "message_id": "message-1",
            "user_message": "我今天有点累",
            "context_items": [],
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["safety"]["level"] == "normal"
    assert body["content"]
