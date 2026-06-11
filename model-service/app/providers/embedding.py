import json
import os
import socket
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any


DEFAULT_EMBEDDING_BASE_URL = "https://openrouter.ai/api/v1"
DEFAULT_EMBEDDING_MODEL = "nvidia/llama-nemotron-embed-vl-1b-v2:free"
DEFAULT_EMBEDDING_DIMENSION = 2048


class EmbeddingUnavailable(RuntimeError):
    pass


@dataclass(frozen=True)
class OpenRouterEmbeddingClient:
    api_key: str | None
    model: str = DEFAULT_EMBEDDING_MODEL
    base_url: str = DEFAULT_EMBEDDING_BASE_URL
    dimension: int = DEFAULT_EMBEDDING_DIMENSION
    timeout_seconds: float = 20.0

    @classmethod
    def from_env(cls) -> "OpenRouterEmbeddingClient":
        return cls(
            api_key=os.getenv("CHRONO_OPENROUTER_API_KEY") or os.getenv("OPENROUTER_API_KEY"),
            model=os.getenv("CHRONO_OPENROUTER_EMBEDDING_MODEL", os.getenv("CHRONO_EMBEDDING_MODEL", DEFAULT_EMBEDDING_MODEL)),
            base_url=os.getenv("CHRONO_OPENROUTER_BASE_URL", DEFAULT_EMBEDDING_BASE_URL).rstrip("/"),
            dimension=int(os.getenv("CHRONO_EMBEDDING_DIMENSION", str(DEFAULT_EMBEDDING_DIMENSION))),
            timeout_seconds=float(os.getenv("CHRONO_OPENROUTER_EMBEDDING_TIMEOUT_SECONDS", os.getenv("CHRONO_EMBEDDING_TIMEOUT_SECONDS", "20"))),
        )

    def embed(self, text: str) -> list[float]:
        if not self.api_key:
            raise EmbeddingUnavailable("OpenRouter API key is not configured")
        if not text.strip():
            raise EmbeddingUnavailable("cannot embed empty text")

        payload = {
            "model": self.model,
            "input": text,
            "dimensions": self.dimension,
        }
        request = urllib.request.Request(
            f"{self.base_url}/embeddings",
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
            raise EmbeddingUnavailable(f"OpenRouter embeddings returned HTTP {error.code}: {trim(body)}") from error
        except (urllib.error.URLError, TimeoutError, socket.timeout, OSError) as error:
            raise EmbeddingUnavailable(f"OpenRouter embeddings request failed: {error}") from error

        try:
            data: dict[str, Any] = json.loads(response_body)
            embedding = data["data"][0]["embedding"]
        except (KeyError, IndexError, TypeError, json.JSONDecodeError) as error:
            raise EmbeddingUnavailable("OpenRouter response did not contain an embedding") from error

        if not isinstance(embedding, list) or not embedding:
            raise EmbeddingUnavailable("OpenRouter returned an empty embedding")
        return [float(value) for value in embedding]


def trim(value: str, limit: int = 500) -> str:
    value = " ".join(value.split())
    if len(value) <= limit:
        return value
    return value[:limit] + "..."
