from fastapi.testclient import TestClient

from app import main
from app.main import app
from app.providers.dashvector import DashVectorUnavailable
from app.schemas import VectorDocument, VectorSearchRequest, VectorSearchResult, VectorUpsertRequest
from app.services.vector_memory import VectorMemoryService


class FakeEmbeddingClient:
    def embed(self, text: str) -> list[float]:
        return [float(len(text)), 1.0, 0.0]


class FakeVectorStore:
    def __init__(self) -> None:
        self.documents = []
        self.vectors = []

    def upsert(self, documents, vectors):  # noqa: ANN001
        self.documents = documents
        self.vectors = vectors
        return len(documents)

    def search(self, vector, user_id: str, top_k: int):  # noqa: ANN001
        return [
            VectorSearchResult(
                document_id="memory_item_1",
                source_type="memory_item",
                source_id="1",
                content=f"{user_id} 的长期记忆",
                reason="DashVector 相似内容召回",
                score=0.81,
            )
        ]


def test_vector_memory_upserts_documents_with_embeddings() -> None:
    vector_store = FakeVectorStore()
    service = VectorMemoryService(embedding_client=FakeEmbeddingClient(), vector_store=vector_store)

    result = service.upsert(
        VectorUpsertRequest(
            request_id="req-1",
            documents=[
                VectorDocument(
                    document_id="conversation_memory_1",
                    user_id="user-1",
                    source_type="conversation_memory",
                    source_id="1",
                    content="用户提到今天疲惫。",
                    reason="录音分析形成的会话记录",
                )
            ],
        )
    )

    assert result.indexed_count == 1
    assert vector_store.documents[0].document_id == "conversation_memory_1"
    assert vector_store.vectors == [[float(len("用户提到今天疲惫。")), 1.0, 0.0]]


def test_vector_memory_search_returns_dashvector_items() -> None:
    service = VectorMemoryService(embedding_client=FakeEmbeddingClient(), vector_store=FakeVectorStore())

    result = service.search(
        VectorSearchRequest(
            request_id="req-1",
            user_id="user-1",
            query="我现在状态怎么样？",
            top_k=8,
        )
    )

    assert result.items[0].source_type == "memory_item"
    assert result.items[0].content == "user-1 的长期记忆"


def test_vector_search_api_returns_502_when_dashvector_unavailable(monkeypatch) -> None:
    class FailingVectorService:
        def search(self, request):  # noqa: ANN001
            raise DashVectorUnavailable("DashVector API key or endpoint is not configured")

    monkeypatch.setattr(main, "vector_service", FailingVectorService())
    client = TestClient(app)

    response = client.post(
        "/v1/vector/search",
        json={
            "request_id": "req-1",
            "user_id": "user-1",
            "query": "最近状态",
            "top_k": 8,
        },
    )

    assert response.status_code == 502
    assert "DashVector API key or endpoint is not configured" in response.json()["detail"]
