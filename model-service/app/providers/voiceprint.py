from typing import Protocol


class VoiceprintProvider(Protocol):
    def similarity(self, left_embedding_ref: str, right_embedding_ref: str) -> float:
        """Return an account-scoped similarity score for two encrypted embedding refs."""
