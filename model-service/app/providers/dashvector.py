import os
from dataclasses import dataclass
from typing import Any

from app.schemas import VectorDocument, VectorSearchResult


DEFAULT_COLLECTION = "chrono_agent_memory"


class DashVectorUnavailable(RuntimeError):
    pass


@dataclass(frozen=True)
class DashVectorStore:
    api_key: str | None
    endpoint: str | None
    collection_name: str = DEFAULT_COLLECTION

    @classmethod
    def from_env(cls) -> "DashVectorStore":
        return cls(
            api_key=os.getenv("CHRONO_DASHVECTOR_API_KEY") or os.getenv("DASHVECTOR_API_KEY"),
            endpoint=os.getenv("CHRONO_DASHVECTOR_ENDPOINT") or os.getenv("DASHVECTOR_ENDPOINT"),
            collection_name=os.getenv("CHRONO_DASHVECTOR_COLLECTION", DEFAULT_COLLECTION),
        )

    def upsert(self, documents: list[VectorDocument], vectors: list[list[float]]) -> int:
        if len(documents) != len(vectors):
            raise DashVectorUnavailable("document count does not match vector count")
        if not documents:
            return 0

        collection = self._collection()
        docs = []
        doc_type = self._doc_type()
        for document, vector in zip(documents, vectors):
            docs.append(doc_type(
                id=document.document_id,
                vector=vector,
                fields={
                    "user_id": document.user_id,
                    "source_type": document.source_type,
                    "source_id": document.source_id,
                    "content": document.content,
                    "reason": document.reason,
                    "created_at": document.created_at or "",
                },
            ))

        response = collection.upsert(docs)
        ensure_success(response, "DashVector upsert failed")
        return len(docs)

    def search(self, vector: list[float], user_id: str, top_k: int) -> list[VectorSearchResult]:
        collection = self._collection()
        response = collection.query(
            vector=vector,
            topk=max(1, min(top_k, 20)),
            filter=f'user_id = "{escape_filter_value(user_id)}"',
            include_vector=False,
        )
        ensure_success(response, "DashVector query failed")
        return [to_search_result(doc) for doc in response_docs(response)]

    def _collection(self) -> Any:
        if not self.api_key or not self.endpoint:
            raise DashVectorUnavailable("DashVector API key or endpoint is not configured")

        try:
            from dashvector import Client
        except ImportError as error:
            raise DashVectorUnavailable("dashvector package is not installed") from error

        try:
            client = Client(api_key=self.api_key, endpoint=self.endpoint)
            collection = client.get(self.collection_name)
        except Exception as error:
            raise DashVectorUnavailable(f"failed to connect DashVector: {error}") from error

        if collection is None:
            raise DashVectorUnavailable(f"DashVector collection not found: {self.collection_name}")
        return collection

    @staticmethod
    def _doc_type() -> Any:
        try:
            from dashvector import Doc
        except ImportError as error:
            raise DashVectorUnavailable("dashvector package is not installed") from error
        return Doc


def ensure_success(response: Any, message: str) -> None:
    code = getattr(response, "code", None)
    if code in (None, 0):
        return
    detail = getattr(response, "message", None) or getattr(response, "msg", None) or str(response)
    raise DashVectorUnavailable(f"{message}: {detail}")


def response_docs(response: Any) -> list[Any]:
    output = getattr(response, "output", response)
    if output is None:
        return []
    if isinstance(output, dict):
        docs = output.get("docs") or output.get("items") or output.get("documents") or []
        return list(docs)
    return list(output)


def to_search_result(doc: Any) -> VectorSearchResult:
    fields = doc.get("fields", {}) if isinstance(doc, dict) else getattr(doc, "fields", {}) or {}
    document_id = doc.get("id") if isinstance(doc, dict) else getattr(doc, "id", "")
    score = doc.get("score") if isinstance(doc, dict) else getattr(doc, "score", 0.0)
    return VectorSearchResult(
        document_id=str(document_id),
        source_type=str(fields.get("source_type", "")),
        source_id=str(fields.get("source_id", "")),
        content=str(fields.get("content", "")),
        reason=str(fields.get("reason", "DashVector 相似内容召回")),
        score=float(score or 0.0),
    )


def escape_filter_value(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"')
