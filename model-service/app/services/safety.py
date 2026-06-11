from app.schemas import SafetyResult


class SafetyService:
    def classify_text(self, text: str) -> SafetyResult:
        if any(keyword in text for keyword in ("自杀", "伤害自己", "不想活")):
            return SafetyResult(level="crisis", requires_crisis_response=True, reason="self_harm_signal")
        return SafetyResult(level="normal")
