from app.schemas import AgentReplyRequest, AgentReplyResponse, SafetyResult


class GenerateReplyService:
    def generate(self, request: AgentReplyRequest) -> AgentReplyResponse:
        return AgentReplyResponse(
            content="我会先帮你做一个温和复盘。",
            safety=SafetyResult(level="normal"),
            memory_candidates=[],
        )
