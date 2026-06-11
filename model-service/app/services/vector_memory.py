from app.providers.dashvector import DashVectorStore
from app.providers.embedding import OpenRouterEmbeddingClient
from app.schemas import (
    VectorSearchRequest,
    VectorSearchResponse,
    VectorUpsertRequest,
    VectorUpsertResponse,
)


class VectorMemoryService:
    def __init__(
        self,
        embedding_client: OpenRouterEmbeddingClient | None = None,
        vector_store: DashVectorStore | None = None,
    ) -> None:
        self.embedding_client = embedding_client or OpenRouterEmbeddingClient.from_env()
        self.vector_store = vector_store or DashVectorStore.from_env()

    def upsert(self, request: VectorUpsertRequest) -> VectorUpsertResponse:
        documents = [document for document in request.documents if document.content.strip()]
        vectors = [self.embedding_client.embed(document.content) for document in documents]
        indexed_count = self.vector_store.upsert(documents, vectors)
        return VectorUpsertResponse(indexed_count=indexed_count)

    def search(self, request: VectorSearchRequest) -> VectorSearchResponse:
        vector = self.embedding_client.embed(request.query)
        items = self.vector_store.search(vector, request.user_id, request.top_k)
        return VectorSearchResponse(items=items)
