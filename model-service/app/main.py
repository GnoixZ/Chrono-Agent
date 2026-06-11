from fastapi import FastAPI

from app.schemas import AgentReplyRequest, AgentReplyResponse, AnalyzeAudioRequest, AnalyzeAudioResponse
from app.services.analyze_audio import AnalyzeAudioService
from app.services.generate_reply import GenerateReplyService

app = FastAPI(title="Chrono Model Service", version="0.1.0")
audio_service = AnalyzeAudioService()
reply_service = GenerateReplyService()


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/v1/audio/analyze", response_model=AnalyzeAudioResponse)
def analyze_audio(request: AnalyzeAudioRequest) -> AnalyzeAudioResponse:
    return audio_service.analyze(request)


@app.post("/v1/agent/reply", response_model=AgentReplyResponse)
def generate_reply(request: AgentReplyRequest) -> AgentReplyResponse:
    return reply_service.generate(request)
