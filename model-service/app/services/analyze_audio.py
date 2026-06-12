import os

from app.providers.fake import FakeModelProvider
from app.providers.openrouter_audio import OpenRouterAudioAnalyzeProvider
from app.schemas import AnalyzeAudioRequest, AnalyzeAudioResponse, IncrementalTranscriptRequest, IncrementalTranscriptResponse


class AnalyzeAudioService:
    def __init__(self, provider=None, incremental_provider: FakeModelProvider | None = None) -> None:  # noqa: ANN001
        self.provider = provider or audio_analyze_provider_from_env()
        self.incremental_provider = incremental_provider or FakeModelProvider()

    def analyze(self, request: AnalyzeAudioRequest) -> AnalyzeAudioResponse:
        return self.provider.analyze_audio(request)

    def incremental_transcript(self, request: IncrementalTranscriptRequest) -> IncrementalTranscriptResponse:
        return self.incremental_provider.incremental_transcript(request)


def audio_analyze_provider_from_env():  # noqa: ANN201
    provider = os.getenv("CHRONO_AUDIO_ANALYZE_PROVIDER", "fake").strip().lower()
    if provider == "openrouter":
        return OpenRouterAudioAnalyzeProvider()
    return FakeModelProvider()
