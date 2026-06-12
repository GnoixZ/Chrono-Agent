from app.providers.fake import FakeModelProvider
from app.schemas import AnalyzeAudioRequest, AnalyzeAudioResponse, IncrementalTranscriptRequest, IncrementalTranscriptResponse


class AnalyzeAudioService:
    def __init__(self, provider: FakeModelProvider | None = None) -> None:
        self.provider = provider or FakeModelProvider()

    def analyze(self, request: AnalyzeAudioRequest) -> AnalyzeAudioResponse:
        return self.provider.analyze_audio(request)

    def incremental_transcript(self, request: IncrementalTranscriptRequest) -> IncrementalTranscriptResponse:
        return self.provider.incremental_transcript(request)
