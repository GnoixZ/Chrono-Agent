from fastapi.testclient import TestClient

from app import main
from app.main import app
from app.providers.openrouter import OpenRouterUnavailable
from app.schemas import AgentContextItem, AgentReplyRequest
from app.services.generate_reply import GenerateReplyService


def test_generate_reply_returns_safe_supportive_text() -> None:
    class FakeChatClient:
        def __init__(self) -> None:
            self.messages = []

        def complete(self, messages: list[dict[str, str]]) -> str:
            self.messages = messages
            return "结合最近录音和健康数据，你今天的主要线索是疲惫。"

    chat_client = FakeChatClient()
    service = GenerateReplyService(chat_client=chat_client)

    result = service.generate(
        AgentReplyRequest(
            request_id="req-1",
            user_id="user-1",
            conversation_session_id="session-1",
            message_id="message-1",
            user_message="我现在状态怎么样？",
            context_items=[
                AgentContextItem(
                    source_type="conversation_memory",
                    source_id="memory-1",
                    content="用户提到今天疲惫，但仍想完成工作。",
                    reason="最近会话记录",
                    score=0.92,
                )
            ],
        )
    )

    assert result.safety.level == "normal"
    assert result.content == "结合最近录音和健康数据，你今天的主要线索是疲惫。"
    assert "我现在状态怎么样？" in chat_client.messages[1]["content"]
    assert "用户提到今天疲惫" in chat_client.messages[1]["content"]


def test_generate_reply_api_returns_502_when_openrouter_is_unavailable(monkeypatch) -> None:
    class FailingReplyService:
        def generate(self, request):  # noqa: ANN001
            raise OpenRouterUnavailable("OpenRouter API key is not configured")

    monkeypatch.setattr(main, "reply_service", FailingReplyService())
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

    assert response.status_code == 502
    body = response.json()
    assert "OpenRouter API key is not configured" in body["detail"]
