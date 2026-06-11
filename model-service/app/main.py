from fastapi import FastAPI, HTTPException

from app.config import load_local_env
from app.providers.dashvector import DashVectorUnavailable
from app.providers.embedding import EmbeddingUnavailable
from app.providers.openrouter import OpenRouterUnavailable
from app.schemas import (
    AgentReplyRequest,
    AgentReplyResponse,
    AnalyzeAudioRequest,
    AnalyzeAudioResponse,
    VectorSearchRequest,
    VectorSearchResponse,
    VectorUpsertRequest,
    VectorUpsertResponse,
)
from app.services.analyze_audio import AnalyzeAudioService
from app.services.generate_reply import GenerateReplyService
from app.services.vector_memory import VectorMemoryService

load_local_env()

app = FastAPI(title="Chrono Model Service", version="0.1.0")
audio_service = AnalyzeAudioService()
reply_service = GenerateReplyService()
vector_service = VectorMemoryService()


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/v1/audio/analyze", response_model=AnalyzeAudioResponse)
def analyze_audio(request: AnalyzeAudioRequest) -> AnalyzeAudioResponse:
    return audio_service.analyze(request)


@app.post("/v1/agent/reply", response_model=AgentReplyResponse)
def generate_reply(request: AgentReplyRequest) -> AgentReplyResponse:
    try:
        return reply_service.generate(request)
    except OpenRouterUnavailable as error:
        raise HTTPException(status_code=502, detail=str(error)) from error


@app.post("/v1/vector/upsert", response_model=VectorUpsertResponse)
def upsert_vectors(request: VectorUpsertRequest) -> VectorUpsertResponse:
    try:
        return vector_service.upsert(request)
    except (DashVectorUnavailable, EmbeddingUnavailable) as error:
        raise HTTPException(status_code=502, detail=str(error)) from error


@app.post("/v1/vector/search", response_model=VectorSearchResponse)
def search_vectors(request: VectorSearchRequest) -> VectorSearchResponse:
    try:
        return vector_service.search(request)
    except (DashVectorUnavailable, EmbeddingUnavailable) as error:
        raise HTTPException(status_code=502, detail=str(error)) from error
