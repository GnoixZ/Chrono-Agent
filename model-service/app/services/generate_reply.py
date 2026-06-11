from app.providers.openrouter import OpenRouterChatClient
from app.schemas import AgentReplyRequest, AgentReplyResponse, SafetyResult


class GenerateReplyService:
    def __init__(self, chat_client: OpenRouterChatClient | None = None) -> None:
        self.chat_client = chat_client or OpenRouterChatClient.from_env()

    def generate(self, request: AgentReplyRequest) -> AgentReplyResponse:
        content = self.chat_client.complete(build_messages(request))
        return AgentReplyResponse(
            content=content,
            safety=SafetyResult(level="normal"),
            memory_candidates=[],
        )


def build_messages(request: AgentReplyRequest) -> list[dict[str, str]]:
    return [
        {
            "role": "system",
            "content": (
                "你是 Chrono Agent，一个面向智能项链场景的中文个人助手。"
                "你根据用户授权记录的录音摘要、健康事件、长期记忆和人物洞察提供心理支持和生活建议。"
                "回复必须温和、具体、可执行；不要做医学诊断、心理治疗判断、药物建议或危机场景承诺。"
                "如果上下文不足，明确说明只能基于当前已记录的数据判断。"
                "不要编造未出现的事实。"
            ),
        },
        {
            "role": "user",
            "content": user_prompt(request),
        },
    ]


def user_prompt(request: AgentReplyRequest) -> str:
    context_text = format_context(request)
    return (
        f"用户当前问题：{request.user_message}\n\n"
        f"可用召回上下文：\n{context_text}\n\n"
        "请用中文回答。结构建议："
        "先直接回应用户问题；然后用 2-4 条要点说明你从上下文看到的状态线索；"
        "最后给 1-3 个今天可以执行的小建议。"
    )


def format_context(request: AgentReplyRequest) -> str:
    if not request.context_items:
        return "无。"

    lines: list[str] = []
    for index, item in enumerate(request.context_items[:12], start=1):
        source_label = source_type_label(item.source_type)
        lines.append(
            f"{index}. [{source_label}] {item.content} "
            f"(召回原因：{item.reason}，分数：{item.score:.2f})"
        )
    return "\n".join(lines)


def source_type_label(source_type: str) -> str:
    labels = {
        "conversation_memory": "最近录音/会话记录",
        "memory_item": "长期个人记忆",
        "health_event": "健康数据",
        "person_insight": "周围人物洞察",
    }
    return labels.get(source_type, source_type)
