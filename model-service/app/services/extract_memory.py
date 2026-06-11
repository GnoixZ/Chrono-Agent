from app.schemas import MemoryCandidate


class MemoryExtractionService:
    def extract_from_text(self, text: str) -> list[MemoryCandidate]:
        if not text.strip():
            return []
        return []
