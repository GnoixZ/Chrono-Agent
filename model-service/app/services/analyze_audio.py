from app.providers.fake import FakeModelProvider
from app.schemas import AnalyzeAudioRequest, AnalyzeAudioResponse


class AnalyzeAudioService:
    def __init__(self, provider: FakeModelProvider | None = None) -> None:
        self.provider = provider or FakeModelProvider()

    def analyze(self, request: AnalyzeAudioRequest) -> AnalyzeAudioResponse:
        return self.provider.analyze_audio(request)
