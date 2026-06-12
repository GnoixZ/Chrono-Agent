import json
import os
import socket
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any


DEFAULT_MODEL = "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free"
DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"


class OpenRouterUnavailable(RuntimeError):
    pass


@dataclass(frozen=True)
class OpenRouterChatClient:
    api_key: str | None
    model: str = DEFAULT_MODEL
    base_url: str = DEFAULT_BASE_URL
    timeout_seconds: float = 30.0

    @classmethod
    def from_env(cls) -> "OpenRouterChatClient":
        return cls(
            api_key=os.getenv("CHRONO_OPENROUTER_API_KEY") or os.getenv("OPENROUTER_API_KEY"),
            model=os.getenv("CHRONO_OPENROUTER_MODEL", DEFAULT_MODEL),
            base_url=os.getenv("CHRONO_OPENROUTER_BASE_URL", DEFAULT_BASE_URL).rstrip("/"),
            timeout_seconds=float(os.getenv("CHRONO_OPENROUTER_TIMEOUT_SECONDS", "30")),
        )

    def complete(
        self,
        messages: list[dict[str, Any]],
        *,
        temperature: float = 0.35,
        max_tokens: int = 700,
        reasoning_effort: str | None = None,
    ) -> str:
        if not self.api_key:
            raise OpenRouterUnavailable("OpenRouter API key is not configured")

        payload = {
            "model": self.model,
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "stream": False,
        }
        if reasoning_effort:
            payload["reasoning_effort"] = reasoning_effort
        request = urllib.request.Request(
            f"{self.base_url}/chat/completions",
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
                "X-Title": "Chrono Agent",
            },
            method="POST",
        )

        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                response_body = response.read().decode("utf-8")
        except urllib.error.HTTPError as error:
            body = error.read().decode("utf-8", errors="replace")
            raise OpenRouterUnavailable(f"OpenRouter returned HTTP {error.code}: {trim(body)}") from error
        except (urllib.error.URLError, TimeoutError, socket.timeout, OSError) as error:
            raise OpenRouterUnavailable(f"OpenRouter request failed: {error}") from error

        try:
            data: dict[str, Any] = json.loads(response_body)
            content = data["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError, json.JSONDecodeError) as error:
            raise OpenRouterUnavailable("OpenRouter response did not contain a chat completion") from error

        if content is None:
            raise OpenRouterUnavailable("OpenRouter returned an empty chat completion")
        content = str(content).strip()
        if not content:
            raise OpenRouterUnavailable("OpenRouter returned an empty chat completion")
        return content


def trim(value: str, limit: int = 500) -> str:
    value = " ".join(value.split())
    if len(value) <= limit:
        return value
    return value[:limit] + "..."
