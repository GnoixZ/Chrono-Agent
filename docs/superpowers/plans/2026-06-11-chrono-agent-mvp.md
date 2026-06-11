# Chrono Agent MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 构建 Chrono Agent MVP：Java 后端负责产品状态、API、持久化、Agent 编排和审计；Python 模型服务负责音频、声纹、摘要、记忆候选、安全分类和回复生成；PostgreSQL 保存结构化数据。

**架构：** 第一阶段采用 Java Spring Boot 单体后端 + Python FastAPI 模型服务 + PostgreSQL。Java 是唯一可信状态源，Python 尽量无状态，只返回模型分析结果、召回结果和候选内容。MVP 不接外部数据源，不做真实硬件接入；Agent 召回索引使用阿里云 DashVector，PostgreSQL 仍是主存储。

**技术栈：** Java 21、Spring Boot、Spring WebSocket、Maven、PostgreSQL、Flyway、Spring Data JPA、JUnit、`fastjson2`、Python 3.11+、FastAPI、Pydantic、pytest、Docker Compose。

---

## 0. 本次计划调整结论

对照中文技术方案后，原实施计划需要调整。

需要补齐的内容：

- 数据库 schema 需要补齐 `audio_stream_session`、`speaker_embedding`、`speaker_label_suggestion`、`person_label_history`、`person_insight`、`model_job`、`audit_log`。
- 音频流实现需要先落 `audio_stream_session`，不能只在 API 层声明流式接口。
- 模型任务不能只写在服务逻辑里，需要有 `model_job` 表支撑任务状态、幂等、重试和错误追踪。
- 声纹和人物能力不能只做标签规则，需要包含声纹样本引用、匿名聚类、标签建议、用户标注历史和人物洞察。
- Agent 和 Memory 任务需要明确短期上下文、会话消息、Agent run、个人记忆、候选记忆和召回审计的写入顺序。
- 隐私安全任务需要覆盖审计日志、日志脱敏、删除策略、声纹向量处理边界和原始音频保留策略。
- 文档语言需要统一为中文，保留必要英文类名、路径、命令和 API 字段名。

## 1. 依赖确认门禁

执行 Task 1 前必须先向用户确认是否批准新增依赖，因为项目级指令要求新增依赖前说明原因、收益和代价。

需要新增的依赖：

- Java 后端：Spring Boot Web、Spring WebSocket、Validation、Data JPA、Actuator、Flyway、PostgreSQL Driver、H2 Test Database、`fastjson2`。
- Python 模型服务：FastAPI、Uvicorn、Pydantic、pytest、httpx。
- 本地开发：Docker Compose PostgreSQL。

收益：

- Spring Boot 快速建立 Java HTTP API、参数校验、持久化、健康检查和测试框架。
- Spring WebSocket 支持浏览器麦克风音频片段流 Demo。
- `fastjson2` 固化 Java/Python JSON 字段映射，避免依赖 Spring MVC 消息转换器调用 Python 模型服务。
- FastAPI 适合承载 Python 模型服务，Pydantic 可以保证 Java/Python 间 schema 稳定。
- PostgreSQL 与当前领域模型匹配，支持 JSONB、索引、事务和后续扩展。
- Flyway 保证数据库 schema 可追踪、可迁移。

代价：

- 本地需要 Java 21、Python 3.11+ 和 Docker。
- 初始工程会包含 Maven、虚拟环境和 Docker Compose 配置。
- PostgreSQL 启动和测试会增加本地资源占用。

确认话术：

```text
执行实现前需要新增 Spring Boot、PostgreSQL、Flyway、FastAPI、pytest、Docker Compose 等依赖。收益是快速建立可测试的 Java 后端和 Python 模型服务；代价是本地环境需要 Java 21、Python 3.11+ 和 Docker。请确认是否批准添加这些依赖。
```

## 2. 文件结构

目标文件结构：

```text
I:\Chrono Agent
  README.md
  docker-compose.yml
  .gitignore
  scripts
    verify-mvp.ps1
  docs
    superpowers
      specs
        2026-06-11-chrono-agent-design.md
        2026-06-11-chrono-agent-technical-solution.md
      plans
        2026-06-11-chrono-agent-mvp.md
  backend
    pom.xml
    mvnw
    mvnw.cmd
    src
      main
        java
          ai
            chrono
              backend
                ChronoBackendApplication.java
                common
                user
                demo
                audio
                health
                modelclient
                task
                conversation
                speaker
                memory
                agent
                timeline
                safety
                audit
                websocket
        resources
          static
            index.html
            app.js
            styles.css
          application.yml
          db
            migration
              V1__core_schema.sql
      test
        java
          ai
            chrono
              backend
  model-service
    pyproject.toml
    app
      __init__.py
      main.py
      schemas.py
      services
        __init__.py
        analyze_audio.py
        generate_reply.py
        extract_memory.py
        safety.py
      providers
        __init__.py
        fake.py
        voiceprint.py
    tests
      test_health.py
      test_analyze_audio.py
      test_generate_reply.py
```

模块边界：

- `backend/common`：通用异常、响应、日志脱敏、时间和 ID。
- `backend/demo`：本地浏览器 Demo API、状态聚合和端到端演示 pipeline。
- `backend/audio`：音频上传、流式会话、音频对象引用和音频事件。
- `backend/health`：健康事件写入和查询。
- `backend/task`：模型任务、状态机、重试和幂等。
- `backend/modelclient`：Python 模型服务 client 和 DTO。
- `backend/conversation`：会话记录、后处理状态机、低价值过滤。
- `backend/speaker`：匿名说话人聚类、声纹样本引用、标签建议、用户标注历史、人物洞察。
- `backend/memory`：短期上下文构造、长期个人记忆、候选记忆、召回审计。
- `backend/agent`：Agent 会话、消息、Agent run、回复编排。
- `backend/safety`：危机风险、输出限制、敏感内容规则。
- `backend/audit`：敏感数据操作审计。
- `backend/websocket`：Spring WebSocket 音频片段流入口 `/ws/audio`。
- `backend/src/main/resources/static`：Chrono Agent Demo 工作台。
- `model-service/providers`：模型 provider adapter；音频分析 MVP 先用 fake provider，Agent 回复使用 OpenRouter，召回索引使用 OpenRouter embeddings + DashVector。
- `model-service/services`：组合 provider 的业务服务。

## 3. 执行规则

- 每个 Task 完成后先运行该 Task 的验证命令。
- 当前目录不是 git 仓库，不执行 commit。
- 如果后续用户明确初始化 git 并批准提交，每个 Task 的检查点只允许添加本 Task 涉及文件，不能使用 `git add .`。
- Java/Python 之间的字段名以 `model-service/app/schemas.py` 和 `backend/modelclient/dto` 为准。
- Java/Python 模型服务 HTTP JSON 边界使用 `RestTemplate` + `fastjson2`，字段名保持 snake_case。
- 本地 Demo API 面向浏览器时可以保留 Java 风格字段，例如 `userId`；模型服务边界不得混用 camelCase。
- WebSocket 音频流使用 Spring WebSocket 注册 `/ws/audio`。
- Python 不直接访问 PostgreSQL。
- Java 不在日志中输出完整转写、心理状态原文、声纹向量或完整 prompt。
- 所有用户数据查询必须带 `user_id` 过滤。

---

## Task 1：工作区初始化

**Files:**

- Create: `README.md`
- Create: `.gitignore`
- Create: `docker-compose.yml`
- Create: `scripts/verify-mvp.ps1`

- [ ] **Step 1：确认依赖授权**

向用户发送第 1 节的确认话术。只有用户明确批准后继续创建工程依赖文件。

期望结果：用户明确批准新增依赖。

- [ ] **Step 2：创建目录结构**

PowerShell 命令：

```powershell
New-Item -ItemType Directory -Force -Path backend, model-service, scripts | Out-Null
New-Item -ItemType Directory -Force -Path model-service\app, model-service\app\services, model-service\app\providers, model-service\tests | Out-Null
```

期望结果：目录创建成功。

- [ ] **Step 3：创建 `.gitignore`**

文件内容：

```gitignore
.idea/
.vscode/
target/
*.class
.mvn/wrapper/maven-wrapper.jar
.venv/
__pycache__/
.pytest_cache/
*.pyc
node_modules/
.DS_Store
audio-data/
tmp/
.env
```

验证命令：

```powershell
Get-Content -Path .gitignore -Encoding UTF8
```

期望结果：能看到上面的忽略规则。

- [ ] **Step 4：创建 `docker-compose.yml`**

文件内容：

```yaml
services:
  postgres:
    image: postgres:16
    container_name: chrono-agent-postgres
    environment:
      POSTGRES_DB: chrono_agent
      POSTGRES_USER: chrono
      POSTGRES_PASSWORD: chrono
    ports:
      - "5432:5432"
    volumes:
      - chrono-postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U chrono -d chrono_agent"]
      interval: 5s
      timeout: 5s
      retries: 10

volumes:
  chrono-postgres-data:
```

验证命令：

```powershell
docker compose config
```

期望结果：Docker Compose 配置解析成功。

- [ ] **Step 5：创建 `README.md`**

文件内容：

```markdown
# Chrono Agent

Chrono Agent 是面向智能项链场景的个人 Agent MVP。

## 模块

- `backend`：Java Spring Boot 后端，负责 API、持久化、Agent 编排和审计。
- `model-service`：Python FastAPI 模型服务，负责音频、声纹、摘要、候选记忆和回复生成。
- `docker-compose.yml`：本地 PostgreSQL。

## 本地开发

1. 启动 PostgreSQL：

   ```powershell
   docker compose up -d postgres
   ```

2. 运行 Java 后端测试：

   ```powershell
   cd backend
   .\mvnw test
   ```

3. 运行 Python 模型服务测试：

   ```powershell
   cd model-service
   .\.venv\Scripts\python -m pytest
   ```

## 当前范围

- 不接外部数据源。
- 不做真实硬件固件或蓝牙接入。
- 不做跨账号声纹匹配。
- 不提供医学诊断或心理治疗。
```

验证命令：

```powershell
Get-Content -Path README.md -Encoding UTF8 -TotalCount 40
```

期望结果：显示中文 README。

---

## Task 2：Java 后端骨架

**Files:**

- Create: `backend/pom.xml`
- Create: `backend/src/main/java/ai/chrono/backend/ChronoBackendApplication.java`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/java/ai/chrono/backend/ChronoBackendApplicationTests.java`

- [ ] **Step 1：生成 Spring Boot 项目**

使用 Spring Initializr 生成 Maven 项目，参数：

- Project：Maven
- Language：Java
- Spring Boot：当前稳定版
- Group：`ai.chrono`
- Artifact：`backend`
- Package：`ai.chrono.backend`
- Java：21
- Dependencies：Spring Web、Validation、Spring Data JPA、Actuator、Flyway Migration、PostgreSQL Driver、H2 Database

如果使用网页生成后解压到 `backend`，确认 `pom.xml` 位于 `backend/pom.xml`。

验证命令：

```powershell
Test-Path backend\pom.xml
```

期望结果：输出 `True`。

- [ ] **Step 2：确认主类**

文件内容：

```java
package ai.chrono.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ChronoBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChronoBackendApplication.class, args);
    }
}
```

- [ ] **Step 3：配置 `application.yml`**

文件内容：

```yaml
spring:
  application:
    name: chrono-agent-backend
  datasource:
    url: jdbc:postgresql://localhost:5432/chrono_agent
    username: chrono
    password: chrono
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true

management:
  endpoints:
    web:
      exposure:
        include: health,info

chrono:
  audio:
    storage-root: ./audio-data
  model-service:
    base-url: http://localhost:8000
```

- [ ] **Step 4：添加启动测试**

文件内容：

```java
package ai.chrono.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ChronoBackendApplicationTests {
    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 5：运行测试**

命令：

```powershell
cd backend
.\mvnw test
```

期望结果：测试通过。

---

## Task 3：Python 模型服务骨架

**Files:**

- Create: `model-service/pyproject.toml`
- Create: `model-service/app/__init__.py`
- Create: `model-service/app/main.py`
- Create: `model-service/tests/test_health.py`

- [ ] **Step 1：创建 Python 项目配置**

文件内容：

```toml
[project]
name = "chrono-model-service"
version = "0.1.0"
requires-python = ">=3.11"
dependencies = [
    "fastapi>=0.111.0",
    "uvicorn[standard]>=0.30.0",
    "pydantic>=2.7.0"
]

[project.optional-dependencies]
test = [
    "pytest>=8.2.0",
    "httpx>=0.27.0"
]

[tool.pytest.ini_options]
pythonpath = ["."]
testpaths = ["tests"]
```

- [ ] **Step 2：创建 FastAPI 应用**

文件内容：

```python
from fastapi import FastAPI

app = FastAPI(title="Chrono Model Service", version="0.1.0")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
```

- [ ] **Step 3：添加健康检查测试**

文件内容：

```python
from fastapi.testclient import TestClient

from app.main import app


def test_health_returns_ok() -> None:
    client = TestClient(app)

    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}
```

- [ ] **Step 4：创建虚拟环境并运行测试**

命令：

```powershell
cd model-service
python -m venv .venv
.\.venv\Scripts\python -m pip install -e ".[test]"
.\.venv\Scripts\python -m pytest
```

期望结果：pytest 通过。

---

## Task 4：Java/Python 共享 API 合约

**Files:**

- Create: `model-service/app/schemas.py`
- Modify: `model-service/app/main.py`
- Create: `backend/src/main/java/ai/chrono/backend/modelclient/dto/AnalyzeAudioRequest.java`
- Create: `backend/src/main/java/ai/chrono/backend/modelclient/dto/AnalyzeAudioResponse.java`
- Create: `backend/src/main/java/ai/chrono/backend/modelclient/dto/AgentReplyRequest.java`
- Create: `backend/src/main/java/ai/chrono/backend/modelclient/dto/AgentReplyResponse.java`

- [ ] **Step 1：定义 Python schema**

`model-service/app/schemas.py` 内容：

```python
from pydantic import BaseModel, Field


class KnownSpeaker(BaseModel):
    speaker_cluster_id: str
    display_name: str
    embedding_refs: list[str] = Field(default_factory=list)


class AnalyzeAudioRequest(BaseModel):
    request_id: str
    user_id: str
    audio_event_id: str
    audio_uri: str
    started_at: str
    ended_at: str | None = None
    known_speakers: list[KnownSpeaker] = Field(default_factory=list)


class SpeakerSegment(BaseModel):
    speaker_id: int
    start_ms: int
    end_ms: int
    transcript: str
    confidence: float
    emotion_tags: list[str] = Field(default_factory=list)
    topic_tags: list[str] = Field(default_factory=list)


class SpeakerEmbedding(BaseModel):
    speaker_id: int
    embedding_ref: str
    quality_score: float


class SuggestedAction(BaseModel):
    type: str
    text: str


class ConversationSummary(BaseModel):
    title: str
    overview: str
    topic_tags: list[str] = Field(default_factory=list)
    emotion_tags: list[str] = Field(default_factory=list)
    suggested_actions: list[SuggestedAction] = Field(default_factory=list)
    suggested_events: list[dict] = Field(default_factory=list)
    discard: bool = False
    discard_reason: str | None = None


class MemoryCandidate(BaseModel):
    memory_type: str
    content: str
    confidence: float
    sensitivity: str = "normal"


class SafetyResult(BaseModel):
    level: str
    requires_crisis_response: bool = False
    reason: str | None = None


class AnalyzeAudioResponse(BaseModel):
    language: str
    segments: list[SpeakerSegment] = Field(default_factory=list)
    speaker_embeddings: list[SpeakerEmbedding] = Field(default_factory=list)
    summary: ConversationSummary
    memory_candidates: list[MemoryCandidate] = Field(default_factory=list)
    safety: SafetyResult


class AgentContextItem(BaseModel):
    source_type: str
    source_id: str
    content: str
    reason: str
    score: float


class AgentReplyRequest(BaseModel):
    request_id: str
    user_id: str
    conversation_session_id: str
    message_id: str
    user_message: str
    context_items: list[AgentContextItem] = Field(default_factory=list)


class AgentReplyResponse(BaseModel):
    content: str
    safety: SafetyResult
    memory_candidates: list[MemoryCandidate] = Field(default_factory=list)
```

- [ ] **Step 2：在 Python 暴露初始接口**

`model-service/app/main.py` 内容：

```python
from fastapi import FastAPI

from app.schemas import (
    AgentReplyRequest,
    AgentReplyResponse,
    AnalyzeAudioRequest,
    AnalyzeAudioResponse,
    ConversationSummary,
    SafetyResult,
)

app = FastAPI(title="Chrono Model Service", version="0.1.0")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/v1/audio/analyze", response_model=AnalyzeAudioResponse)
def analyze_audio(request: AnalyzeAudioRequest) -> AnalyzeAudioResponse:
    return AnalyzeAudioResponse(
        language="zh",
        segments=[],
        speaker_embeddings=[],
        summary=ConversationSummary(
            title="空音频",
            overview="当前请求还没有接入真实分析。",
            discard=True,
            discard_reason="empty_fake_response",
        ),
        memory_candidates=[],
        safety=SafetyResult(level="normal"),
    )


@app.post("/v1/agent/reply", response_model=AgentReplyResponse)
def generate_reply(request: AgentReplyRequest) -> AgentReplyResponse:
    return AgentReplyResponse(
        content="我会先帮你做一个温和复盘。",
        safety=SafetyResult(level="normal"),
        memory_candidates=[],
    )
```

- [ ] **Step 3：定义 Java DTO**

`AnalyzeAudioRequest.java`：

```java
package ai.chrono.backend.modelclient.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import java.util.List;

public record AnalyzeAudioRequest(
        @JSONField(name = "request_id") String requestId,
        @JSONField(name = "user_id") String userId,
        @JSONField(name = "audio_event_id") String audioEventId,
        @JSONField(name = "audio_uri") String audioUri,
        @JSONField(name = "started_at") String startedAt,
        @JSONField(name = "ended_at") String endedAt,
        @JSONField(name = "known_speakers") List<KnownSpeakerDto> knownSpeakers
) {
    public record KnownSpeakerDto(
            @JSONField(name = "speaker_cluster_id") String speakerClusterId,
            @JSONField(name = "display_name") String displayName,
            @JSONField(name = "embedding_refs") List<String> embeddingRefs
    ) {
    }
}
```

`AnalyzeAudioResponse.java`：

```java
package ai.chrono.backend.modelclient.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import java.util.List;
import java.util.Map;

public record AnalyzeAudioResponse(
        String language,
        List<SpeakerSegmentDto> segments,
        @JSONField(name = "speaker_embeddings") List<SpeakerEmbeddingDto> speakerEmbeddings,
        ConversationSummaryDto summary,
        @JSONField(name = "memory_candidates") List<MemoryCandidateDto> memoryCandidates,
        SafetyResultDto safety
) {
    public record SpeakerSegmentDto(
            @JSONField(name = "speaker_id") Integer speakerId,
            @JSONField(name = "start_ms") Integer startMs,
            @JSONField(name = "end_ms") Integer endMs,
            String transcript,
            Double confidence,
            @JSONField(name = "emotion_tags") List<String> emotionTags,
            @JSONField(name = "topic_tags") List<String> topicTags
    ) {
    }

    public record SpeakerEmbeddingDto(
            @JSONField(name = "speaker_id") Integer speakerId,
            @JSONField(name = "embedding_ref") String embeddingRef,
            @JSONField(name = "quality_score") Double qualityScore
    ) {
    }

    public record SuggestedActionDto(
            String type,
            String text
    ) {
    }

    public record ConversationSummaryDto(
            String title,
            String overview,
            @JSONField(name = "topic_tags") List<String> topicTags,
            @JSONField(name = "emotion_tags") List<String> emotionTags,
            @JSONField(name = "suggested_actions") List<SuggestedActionDto> suggestedActions,
            @JSONField(name = "suggested_events") List<Map<String, Object>> suggestedEvents,
            Boolean discard,
            @JSONField(name = "discard_reason") String discardReason
    ) {
    }

    public record MemoryCandidateDto(
            @JSONField(name = "memory_type") String memoryType,
            String content,
            Double confidence,
            String sensitivity
    ) {
    }

    public record SafetyResultDto(
            String level,
            @JSONField(name = "requires_crisis_response") Boolean requiresCrisisResponse,
            String reason
    ) {
    }
}
```

`AgentReplyRequest.java`：

```java
package ai.chrono.backend.modelclient.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import java.util.List;

public record AgentReplyRequest(
        @JSONField(name = "request_id") String requestId,
        @JSONField(name = "user_id") String userId,
        @JSONField(name = "conversation_session_id") String conversationSessionId,
        @JSONField(name = "message_id") String messageId,
        @JSONField(name = "user_message") String userMessage,
        @JSONField(name = "context_items") List<AgentContextItemDto> contextItems
) {
    public record AgentContextItemDto(
            @JSONField(name = "source_type") String sourceType,
            @JSONField(name = "source_id") String sourceId,
            String content,
            String reason,
            Double score
    ) {
    }
}
```

`AgentReplyResponse.java`：

```java
package ai.chrono.backend.modelclient.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import java.util.List;

public record AgentReplyResponse(
        String content,
        AnalyzeAudioResponse.SafetyResultDto safety,
        @JSONField(name = "memory_candidates") List<AnalyzeAudioResponse.MemoryCandidateDto> memoryCandidates
) {
}
```

- [ ] **Step 4：运行 schema 测试**

命令：

```powershell
cd model-service
.\.venv\Scripts\python -m pytest
```

期望结果：Python 测试通过。

---

## Task 5：数据库 schema 和核心实体

**Files:**

- Create: `backend/src/main/resources/db/migration/V1__core_schema.sql`
- Create: `backend/src/main/java/ai/chrono/backend/conversation/ConversationMemory.java`
- Create: `backend/src/main/java/ai/chrono/backend/conversation/ConversationMemoryRepository.java`
- Test: `backend/src/test/java/ai/chrono/backend/conversation/ConversationMemoryRepositoryTest.java`

- [ ] **Step 1：创建 Flyway schema**

`V1__core_schema.sql` 内容：

```sql
create table audio_stream_session (
    id uuid primary key,
    user_id varchar(128) not null,
    device_id varchar(128),
    source_type varchar(64) not null,
    sample_rate integer,
    codec varchar(32),
    started_at timestamp with time zone not null,
    last_active_at timestamp with time zone not null,
    closed_at timestamp with time zone,
    status varchar(32) not null,
    close_reason text,
    current_audio_event_id uuid,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create unique index ux_audio_stream_one_active
on audio_stream_session(user_id)
where status = 'active';

create index idx_audio_stream_user_started
on audio_stream_session(user_id, started_at desc);

create table audio_event (
    id uuid primary key,
    user_id varchar(128) not null,
    source_type varchar(64) not null,
    started_at timestamp with time zone not null,
    ended_at timestamp with time zone,
    audio_uri text not null,
    processing_status varchar(32) not null,
    stream_session_id uuid references audio_stream_session(id),
    sample_rate integer,
    codec varchar(32),
    duration_ms integer,
    retention_expires_at timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

alter table audio_stream_session
add constraint fk_audio_stream_current_event
foreign key (current_audio_event_id) references audio_event(id);

create index idx_audio_event_user_started
on audio_event(user_id, started_at desc);

create index idx_audio_event_processing
on audio_event(processing_status, created_at);

create table health_event (
    id uuid primary key,
    user_id varchar(128) not null,
    event_type varchar(64) not null,
    measured_at timestamp with time zone not null,
    value_numeric double precision,
    value_text text,
    unit varchar(32),
    source varchar(64) not null,
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamp with time zone not null
);

create index idx_health_event_user_time
on health_event(user_id, measured_at desc);

create index idx_health_event_user_type_time
on health_event(user_id, event_type, measured_at desc);

create table conversation_session (
    id uuid primary key,
    user_id varchar(128) not null,
    title text not null,
    session_type varchar(64) not null,
    started_at timestamp with time zone not null,
    last_message_at timestamp with time zone not null,
    status varchar(32) not null,
    source varchar(64) not null
);

create table agent_message (
    id uuid primary key,
    conversation_session_id uuid not null references conversation_session(id),
    user_id varchar(128) not null,
    role varchar(32) not null,
    content_type varchar(64) not null,
    content text,
    content_ref text,
    source_event_id uuid,
    model_name varchar(128),
    safety_level varchar(64),
    created_at timestamp with time zone not null,
    deleted_at timestamp with time zone
);

create index idx_agent_message_session_time
on agent_message(conversation_session_id, created_at);

create table agent_run (
    id uuid primary key,
    conversation_session_id uuid not null references conversation_session(id),
    trigger_message_id uuid references agent_message(id),
    status varchar(32) not null,
    context_window_start timestamp with time zone,
    context_window_end timestamp with time zone,
    short_term_memory_ref text,
    retrieved_context_ref text,
    model_request_ref text,
    model_response_ref text,
    safety_result jsonb not null default '{}'::jsonb,
    created_at timestamp with time zone not null,
    completed_at timestamp with time zone
);

create table conversation_memory (
    id uuid primary key,
    user_id varchar(128) not null,
    source_type varchar(64) not null,
    source_audio_event_id uuid references audio_event(id),
    source_conversation_session_id uuid references conversation_session(id),
    started_at timestamp with time zone,
    ended_at timestamp with time zone,
    title text not null,
    overview text not null,
    language varchar(16),
    category varchar(64),
    status varchar(32) not null,
    post_processing_status varchar(32) not null,
    processing_attempts integer not null default 0,
    last_error_type varchar(128),
    last_error_message text,
    discarded boolean not null default false,
    discard_reason text,
    visibility varchar(32) not null default 'private',
    transcript_ref text,
    speaker_refs jsonb not null default '[]'::jsonb,
    health_refs jsonb not null default '[]'::jsonb,
    topic_tags jsonb not null default '[]'::jsonb,
    emotion_tags jsonb not null default '[]'::jsonb,
    suggested_actions jsonb not null default '[]'::jsonb,
    suggested_events jsonb not null default '[]'::jsonb,
    embedding_ref text,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    deleted_at timestamp with time zone
);

create index idx_conversation_memory_user_started
on conversation_memory(user_id, started_at desc);

create index idx_conversation_memory_status
on conversation_memory(status, post_processing_status, created_at);

create index idx_conversation_memory_topic_tags
on conversation_memory using gin(topic_tags);

create table speaker_cluster (
    id uuid primary key,
    user_id varchar(128) not null,
    display_name varchar(255) not null,
    status varchar(32) not null,
    created_from varchar(64) not null,
    first_seen_at timestamp with time zone not null,
    last_seen_at timestamp with time zone not null,
    match_confidence_summary jsonb not null default '{}'::jsonb,
    user_labeled boolean not null default false,
    label_suggestion varchar(255),
    label_suggestion_source varchar(64),
    label_suggestion_confidence double precision,
    merged_into_id uuid,
    deleted_at timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index idx_speaker_cluster_user_status
on speaker_cluster(user_id, status);

create index idx_speaker_cluster_user_seen
on speaker_cluster(user_id, last_seen_at desc);

create table speaker_segment (
    id uuid primary key,
    audio_event_id uuid not null references audio_event(id),
    speaker_cluster_id uuid references speaker_cluster(id),
    speaker_id integer not null,
    is_user boolean not null default false,
    person_id uuid,
    start_ms integer not null,
    end_ms integer not null,
    transcript text not null,
    language varchar(16),
    confidence double precision not null,
    emotion_tags jsonb not null default '[]'::jsonb,
    topic_tags jsonb not null default '[]'::jsonb,
    created_at timestamp with time zone not null
);

create index idx_speaker_segment_audio_time
on speaker_segment(audio_event_id, start_ms);

create index idx_speaker_segment_cluster
on speaker_segment(speaker_cluster_id);

create table speaker_embedding (
    id uuid primary key,
    speaker_cluster_id uuid not null references speaker_cluster(id),
    audio_event_id uuid references audio_event(id),
    embedding_ref text not null,
    model_name varchar(128) not null,
    quality_score double precision not null,
    created_at timestamp with time zone not null,
    expires_at timestamp with time zone
);

create table speaker_label_suggestion (
    id uuid primary key,
    speaker_cluster_id uuid not null references speaker_cluster(id),
    suggested_label varchar(255) not null,
    source_type varchar(64) not null,
    evidence_ref text not null,
    confidence double precision not null,
    status varchar(32) not null,
    created_at timestamp with time zone not null,
    decided_at timestamp with time zone
);

create table person_label_history (
    id uuid primary key,
    speaker_cluster_id uuid not null references speaker_cluster(id),
    user_id varchar(128) not null,
    action varchar(32) not null,
    old_value jsonb not null default '{}'::jsonb,
    new_value jsonb not null default '{}'::jsonb,
    reason text,
    created_at timestamp with time zone not null
);

create table person_insight (
    id uuid primary key,
    user_id varchar(128) not null,
    speaker_cluster_id uuid not null references speaker_cluster(id),
    insight_type varchar(64) not null,
    time_window_start timestamp with time zone not null,
    time_window_end timestamp with time zone not null,
    summary text not null,
    evidence_refs jsonb not null default '[]'::jsonb,
    confidence double precision not null,
    safety_level varchar(64) not null,
    created_at timestamp with time zone not null
);

create table memory_item (
    id uuid primary key,
    user_id varchar(128) not null,
    source_type varchar(64) not null,
    memory_type varchar(64) not null,
    scope varchar(64) not null,
    subject_type varchar(64),
    subject_id uuid,
    content text not null,
    confidence double precision not null,
    source varchar(64) not null,
    evidence_refs jsonb not null default '[]'::jsonb,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    valid_at timestamp with time zone not null,
    invalid_at timestamp with time zone,
    superseded_by uuid,
    last_used_at timestamp with time zone,
    expires_at timestamp with time zone,
    deleted_at timestamp with time zone
);

create index idx_memory_item_active_user
on memory_item(user_id, memory_type)
where invalid_at is null and deleted_at is null;

create index idx_memory_item_subject
on memory_item(user_id, subject_type, subject_id)
where invalid_at is null and deleted_at is null;

create table memory_write_candidate (
    id uuid primary key,
    conversation_session_id uuid references conversation_session(id),
    conversation_memory_id uuid references conversation_memory(id),
    source_message_id uuid references agent_message(id),
    source_type varchar(64) not null,
    memory_type varchar(64) not null,
    content text not null,
    confidence double precision not null,
    decision varchar(64) not null,
    decision_reason text,
    created_at timestamp with time zone not null,
    decided_at timestamp with time zone
);

create table memory_recall_event (
    id uuid primary key,
    agent_run_id uuid not null references agent_run(id),
    recall_type varchar(64) not null,
    memory_item_id uuid references memory_item(id),
    conversation_memory_id uuid references conversation_memory(id),
    rank integer not null,
    reason text not null,
    score double precision not null,
    created_at timestamp with time zone not null
);

create table model_job (
    id uuid primary key,
    user_id varchar(128) not null,
    job_type varchar(64) not null,
    source_ref_type varchar(64) not null,
    source_ref_id uuid not null,
    status varchar(32) not null,
    attempts integer not null default 0,
    next_run_at timestamp with time zone not null,
    request_ref text,
    response_ref text,
    last_error_type varchar(128),
    last_error_message text,
    idempotency_key varchar(255) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create unique index ux_model_job_idempotency
on model_job(idempotency_key);

create index idx_model_job_pending
on model_job(status, next_run_at)
where status in ('pending', 'failed');

create table audit_log (
    id uuid primary key,
    user_id varchar(128) not null,
    actor_type varchar(64) not null,
    action varchar(128) not null,
    target_type varchar(64) not null,
    target_id uuid,
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamp with time zone not null
);
```

- [ ] **Step 2：添加最小实体和 repository 测试**

`ConversationMemory.java`：

```java
package ai.chrono.backend.conversation;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;

@Entity
public class ConversationMemory {
    @Id
    private UUID id;
    private String userId;
    private String sourceType;
    private Instant startedAt;
    private Instant endedAt;
    private String title;
    private String overview;
    private String language;
    private String category;
    private String status;
    private String postProcessingStatus;
    private Integer processingAttempts;
    private Boolean discarded;
    private String discardReason;
    private String visibility;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;

    protected ConversationMemory() {
    }

    public ConversationMemory(UUID id, String userId, String sourceType, Instant startedAt, String title, String overview) {
        this.id = id;
        this.userId = userId;
        this.sourceType = sourceType;
        this.startedAt = startedAt;
        this.title = title;
        this.overview = overview;
        this.status = "in_progress";
        this.postProcessingStatus = "not_started";
        this.processingAttempts = 0;
        this.discarded = false;
        this.visibility = "private";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public UUID id() {
        return id;
    }
}
```

`ConversationMemoryRepository.java`：

```java
package ai.chrono.backend.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ConversationMemoryRepository extends JpaRepository<ConversationMemory, UUID> {
}
```

测试：

```java
package ai.chrono.backend.conversation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class ConversationMemoryRepositoryTest {

    @Autowired
    ConversationMemoryRepository repository;

    @Test
    void savesConversationMemoryDraft() {
        ConversationMemory memory = new ConversationMemory(
                UUID.randomUUID(),
                "user-1",
                "audio_recording",
                Instant.parse("2026-06-11T09:00:00Z"),
                "早晨记录",
                "用户进行了一段早晨状态记录"
        );

        ConversationMemory saved = repository.save(memory);

        assertThat(saved.id()).isNotNull();
    }
}
```

- [ ] **Step 3：运行持久化测试**

命令：

```powershell
cd backend
.\mvnw test -Dtest=ConversationMemoryRepositoryTest
```

期望结果：测试通过。

---

## Task 6：模型任务和音频存储

**Files:**

- Create: `backend/src/main/java/ai/chrono/backend/audio/AudioStorage.java`
- Create: `backend/src/main/java/ai/chrono/backend/audio/LocalAudioStorage.java`
- Create: `backend/src/main/java/ai/chrono/backend/task/ModelJobService.java`
- Test: `backend/src/test/java/ai/chrono/backend/task/ModelJobServiceTest.java`

- [ ] **Step 1：定义音频存储接口**

```java
package ai.chrono.backend.audio;

import java.util.Optional;

public interface AudioStorage {
    StoredAudio save(AudioInput input);
    Optional<StoredAudio> find(String audioUri);
    void delete(String audioUri);

    record AudioInput(String userId, String fileName, byte[] bytes) {
    }

    record StoredAudio(String audioUri, long sizeBytes) {
    }
}
```

- [ ] **Step 2：实现本地音频存储**

```java
package ai.chrono.backend.audio;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Component
public class LocalAudioStorage implements AudioStorage {
    private final Path root;

    public LocalAudioStorage(@Value("${chrono.audio.storage-root:./audio-data}") String root) {
        this.root = Path.of(root);
    }

    @Override
    public StoredAudio save(AudioInput input) {
        try {
            LocalDate today = LocalDate.now();
            Path directory = root.resolve(input.userId()).resolve(today.toString());
            Files.createDirectories(directory);
            String safeName = UUID.randomUUID() + "-" + input.fileName().replaceAll("[^a-zA-Z0-9._-]", "_");
            Path file = directory.resolve(safeName);
            Files.write(file, input.bytes());
            return new StoredAudio("local://" + root.relativize(file).toString().replace("\\", "/"), input.bytes().length);
        } catch (IOException error) {
            throw new IllegalStateException("failed to save audio", error);
        }
    }

    @Override
    public Optional<StoredAudio> find(String audioUri) {
        if (audioUri == null || !audioUri.startsWith("local://")) {
            return Optional.empty();
        }
        Path file = root.resolve(audioUri.substring("local://".length()));
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new StoredAudio(audioUri, Files.size(file)));
        } catch (IOException error) {
            throw new IllegalStateException("failed to read audio metadata", error);
        }
    }

    @Override
    public void delete(String audioUri) {
        if (audioUri != null && audioUri.startsWith("local://")) {
            try {
                Files.deleteIfExists(root.resolve(audioUri.substring("local://".length())));
            } catch (IOException error) {
                throw new IllegalStateException("failed to delete audio", error);
            }
        }
    }
}
```

- [ ] **Step 3：实现模型任务服务规则**

```java
package ai.chrono.backend.task;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class ModelJobService {
    public ModelJobDraft createAudioAnalyzeJob(String userId, UUID audioEventId) {
        String idempotencyKey = "audio_analyze:" + audioEventId;
        return new ModelJobDraft(
                UUID.randomUUID(),
                userId,
                "audio_analyze",
                "audio_event",
                audioEventId,
                "pending",
                0,
                Instant.now(),
                idempotencyKey
        );
    }

    public record ModelJobDraft(
            UUID id,
            String userId,
            String jobType,
            String sourceRefType,
            UUID sourceRefId,
            String status,
            int attempts,
            Instant nextRunAt,
            String idempotencyKey
    ) {
    }
}
```

- [ ] **Step 4：测试任务幂等键**

```java
package ai.chrono.backend.task;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ModelJobServiceTest {
    @Test
    void createsAudioAnalyzeJobWithStableIdempotencyKey() {
        UUID audioEventId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        ModelJobService service = new ModelJobService();

        ModelJobService.ModelJobDraft draft = service.createAudioAnalyzeJob("user-1", audioEventId);

        assertThat(draft.jobType()).isEqualTo("audio_analyze");
        assertThat(draft.sourceRefType()).isEqualTo("audio_event");
        assertThat(draft.idempotencyKey()).isEqualTo("audio_analyze:11111111-1111-1111-1111-111111111111");
    }
}
```

运行：

```powershell
cd backend
.\mvnw test -Dtest=ModelJobServiceTest
```

期望结果：测试通过。

---

## Task 7：Python fake 模型流水线

**Files:**

- Create: `model-service/app/providers/fake.py`
- Create: `model-service/app/services/analyze_audio.py`
- Modify: `model-service/app/main.py`
- Test: `model-service/tests/test_analyze_audio.py`

- [ ] **Step 1：实现 fake provider**

```python
from app.schemas import (
    AnalyzeAudioRequest,
    AnalyzeAudioResponse,
    ConversationSummary,
    MemoryCandidate,
    SafetyResult,
    SpeakerEmbedding,
    SpeakerSegment,
    SuggestedAction,
)


class FakeModelProvider:
    def analyze_audio(self, request: AnalyzeAudioRequest) -> AnalyzeAudioResponse:
        if "blank" in request.audio_uri:
            return AnalyzeAudioResponse(
                language="zh",
                segments=[],
                speaker_embeddings=[],
                summary=ConversationSummary(
                    title="低价值录音",
                    overview="录音为空白或信息量过低。",
                    discard=True,
                    discard_reason="blank_or_low_value_audio",
                ),
                memory_candidates=[],
                safety=SafetyResult(level="normal"),
            )

        return AnalyzeAudioResponse(
            language="zh",
            segments=[
                SpeakerSegment(
                    speaker_id=1,
                    start_ms=0,
                    end_ms=4200,
                    transcript="我今天有点累，但还是想把事情做完。",
                    confidence=0.91,
                    emotion_tags=["tired"],
                    topic_tags=["work", "energy"],
                )
            ],
            speaker_embeddings=[
                SpeakerEmbedding(
                    speaker_id=1,
                    embedding_ref=f"encrypted://tmp/{request.audio_event_id}/speaker-1",
                    quality_score=0.86,
                )
            ],
            summary=ConversationSummary(
                title="上午状态记录",
                overview="用户提到今天疲惫，但仍想完成工作。",
                topic_tags=["work", "energy"],
                emotion_tags=["tired"],
                suggested_actions=[
                    SuggestedAction(type="self_care", text="今天安排一个短休息窗口。")
                ],
                discard=False,
            ),
            memory_candidates=[
                MemoryCandidate(
                    memory_type="life_pattern",
                    content="用户在工作日上午容易感到疲惫。",
                    confidence=0.62,
                    sensitivity="normal",
                )
            ],
            safety=SafetyResult(level="normal"),
        )
```

- [ ] **Step 2：接入服务和路由**

`model-service/app/services/analyze_audio.py`：

```python
from app.providers.fake import FakeModelProvider
from app.schemas import AnalyzeAudioRequest, AnalyzeAudioResponse


class AnalyzeAudioService:
    def __init__(self, provider: FakeModelProvider | None = None) -> None:
        self.provider = provider or FakeModelProvider()

    def analyze(self, request: AnalyzeAudioRequest) -> AnalyzeAudioResponse:
        return self.provider.analyze_audio(request)
```

`model-service/app/main.py` 中 `/v1/audio/analyze` 改为：

```python
from app.services.analyze_audio import AnalyzeAudioService

audio_service = AnalyzeAudioService()


@app.post("/v1/audio/analyze", response_model=AnalyzeAudioResponse)
def analyze_audio(request: AnalyzeAudioRequest) -> AnalyzeAudioResponse:
    return audio_service.analyze(request)
```

- [ ] **Step 3：添加测试**

```python
from fastapi.testclient import TestClient

from app.main import app


def test_analyze_audio_returns_segments_and_memory_candidate() -> None:
    client = TestClient(app)

    response = client.post(
        "/v1/audio/analyze",
        json={
            "request_id": "req-1",
            "user_id": "user-1",
            "audio_event_id": "audio-1",
            "audio_uri": "local://audio/user-1/sample.wav",
            "started_at": "2026-06-11T09:00:00Z",
            "ended_at": "2026-06-11T09:01:00Z",
            "known_speakers": [],
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["language"] == "zh"
    assert body["segments"][0]["speaker_id"] == 1
    assert body["speaker_embeddings"][0]["quality_score"] == 0.86
    assert body["memory_candidates"][0]["memory_type"] == "life_pattern"


def test_analyze_audio_discards_blank_audio() -> None:
    client = TestClient(app)

    response = client.post(
        "/v1/audio/analyze",
        json={
            "request_id": "req-2",
            "user_id": "user-1",
            "audio_event_id": "audio-2",
            "audio_uri": "local://audio/user-1/blank.wav",
            "started_at": "2026-06-11T09:00:00Z",
            "known_speakers": [],
        },
    )

    assert response.status_code == 200
    assert response.json()["summary"]["discard"] is True
```

运行：

```powershell
cd model-service
.\.venv\Scripts\python -m pytest
```

期望结果：测试通过。

---

## Task 8：音频上传和流式会话 API

**Files:**

- Create: `backend/src/main/java/ai/chrono/backend/audio/AudioController.java`
- Create: `backend/src/main/java/ai/chrono/backend/audio/AudioService.java`
- Create: `backend/src/main/java/ai/chrono/backend/audio/AudioStreamService.java`
- Test: `backend/src/test/java/ai/chrono/backend/audio/AudioStreamServiceTest.java`

- [ ] **Step 1：定义音频上传响应**

```java
package ai.chrono.backend.audio;

public record AudioUploadResponse(
        String audioEventId,
        String processingStatus,
        String conversationMemoryId
) {
}
```

- [ ] **Step 2：定义流式会话响应**

```java
package ai.chrono.backend.audio;

public record AudioStreamSessionResponse(
        String streamSessionId,
        String status,
        String currentAudioEventId
) {
}
```

- [ ] **Step 3：实现每用户单活跃流式会话规则**

```java
package ai.chrono.backend.audio;

import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Service
public class AudioStreamService {
    private final Set<String> activeUsers = new HashSet<>();

    public AudioStreamSessionResponse open(String userId) {
        if (activeUsers.contains(userId)) {
            throw new IllegalStateException("active audio stream already exists");
        }
        activeUsers.add(userId);
        return new AudioStreamSessionResponse(UUID.randomUUID().toString(), "active", null);
    }

    public AudioStreamSessionResponse close(String userId, String streamSessionId) {
        activeUsers.remove(userId);
        return new AudioStreamSessionResponse(streamSessionId, "closed", null);
    }
}
```

- [ ] **Step 4：测试单活跃会话**

```java
package ai.chrono.backend.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioStreamServiceTest {
    @Test
    void rejectsSecondActiveStreamForSameUser() {
        AudioStreamService service = new AudioStreamService();

        AudioStreamSessionResponse first = service.open("user-1");

        assertThat(first.status()).isEqualTo("active");
        assertThatThrownBy(() -> service.open("user-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("active audio stream already exists");
    }

    @Test
    void allowsNewStreamAfterClose() {
        AudioStreamService service = new AudioStreamService();
        AudioStreamSessionResponse first = service.open("user-1");

        service.close("user-1", first.streamSessionId());
        AudioStreamSessionResponse second = service.open("user-1");

        assertThat(second.status()).isEqualTo("active");
    }
}
```

运行：

```powershell
cd backend
.\mvnw test -Dtest=AudioStreamServiceTest
```

期望结果：测试通过。

---

## Task 9：健康事件和时间线

**Files:**

- Create: `backend/src/main/java/ai/chrono/backend/health/HealthEventRequest.java`
- Create: `backend/src/main/java/ai/chrono/backend/health/HealthEventResponse.java`
- Create: `backend/src/main/java/ai/chrono/backend/health/HealthController.java`
- Create: `backend/src/main/java/ai/chrono/backend/timeline/TimelineService.java`
- Test: `backend/src/test/java/ai/chrono/backend/health/HealthEventRequestTest.java`

- [ ] **Step 1：定义健康事件请求**

```java
package ai.chrono.backend.health;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record HealthEventRequest(
        @NotBlank String userId,
        @NotBlank String eventType,
        @NotNull Instant measuredAt,
        Double valueNumeric,
        String valueText,
        String unit,
        @NotBlank String source
) {
}
```

- [ ] **Step 2：定义响应**

```java
package ai.chrono.backend.health;

public record HealthEventResponse(
        String id,
        String userId,
        String eventType,
        String measuredAt,
        String displayValue
) {
}
```

- [ ] **Step 3：实现事件类型校验**

```java
package ai.chrono.backend.health;

import java.util.Set;

public final class HealthEventTypes {
    private static final Set<String> ALLOWED = Set.of(
            "heart_rate",
            "sleep_duration",
            "steps",
            "activity_minutes",
            "stress_score",
            "mood_check_in"
    );

    private HealthEventTypes() {
    }

    public static boolean isAllowed(String eventType) {
        return ALLOWED.contains(eventType);
    }
}
```

- [ ] **Step 4：测试事件类型**

```java
package ai.chrono.backend.health;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HealthEventRequestTest {
    @Test
    void acceptsMvpHealthEventTypes() {
        assertThat(HealthEventTypes.isAllowed("heart_rate")).isTrue();
        assertThat(HealthEventTypes.isAllowed("mood_check_in")).isTrue();
    }

    @Test
    void rejectsUnsupportedHealthEventType() {
        assertThat(HealthEventTypes.isAllowed("blood_test_report")).isFalse();
    }
}
```

运行：

```powershell
cd backend
.\mvnw test -Dtest=HealthEventRequestTest
```

期望结果：测试通过。

---

## Task 10：会话后处理和候选记忆

**Files:**

- Create: `backend/src/main/java/ai/chrono/backend/conversation/ConversationPostProcessor.java`
- Create: `backend/src/main/java/ai/chrono/backend/memory/MemoryCandidateDecisionService.java`
- Test: `backend/src/test/java/ai/chrono/backend/conversation/ConversationPostProcessorTest.java`
- Test: `backend/src/test/java/ai/chrono/backend/memory/MemoryCandidateDecisionServiceTest.java`

- [ ] **Step 1：实现低价值会话决策**

```java
package ai.chrono.backend.conversation;

import ai.chrono.backend.modelclient.dto.AnalyzeAudioResponse;
import org.springframework.stereotype.Service;

@Service
public class ConversationPostProcessor {
    public PostProcessingDecision decide(AnalyzeAudioResponse response) {
        if (Boolean.TRUE.equals(response.summary().discard())) {
            return new PostProcessingDecision("discarded", response.summary().discardReason());
        }
        return new PostProcessingDecision("completed", null);
    }

    public record PostProcessingDecision(String status, String reason) {
    }
}
```

- [ ] **Step 2：测试低价值丢弃**

```java
package ai.chrono.backend.conversation;

import ai.chrono.backend.modelclient.dto.AnalyzeAudioResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationPostProcessorTest {
    @Test
    void discardsLowValueConversation() {
        AnalyzeAudioResponse response = new AnalyzeAudioResponse(
                "zh",
                List.of(),
                List.of(),
                new AnalyzeAudioResponse.ConversationSummaryDto(
                        "",
                        "",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        true,
                        "blank_or_low_value_audio"
                ),
                List.of(),
                new AnalyzeAudioResponse.SafetyResultDto("normal", false, null)
        );

        ConversationPostProcessor processor = new ConversationPostProcessor();
        ConversationPostProcessor.PostProcessingDecision decision = processor.decide(response);

        assertThat(decision.status()).isEqualTo("discarded");
        assertThat(decision.reason()).isEqualTo("blank_or_low_value_audio");
    }
}
```

- [ ] **Step 3：实现候选记忆决策**

```java
package ai.chrono.backend.memory;

import org.springframework.stereotype.Service;

@Service
public class MemoryCandidateDecisionService {
    public String decide(String sourceType, String sensitivity, double confidence) {
        if ("user_confirmed".equals(sourceType)) {
            return "auto_saved";
        }
        if (!"normal".equals(sensitivity)) {
            return "needs_user_confirmation";
        }
        if (confidence >= 0.75) {
            return "auto_saved";
        }
        return "needs_user_confirmation";
    }
}
```

- [ ] **Step 4：测试候选记忆决策**

```java
package ai.chrono.backend.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryCandidateDecisionServiceTest {
    @Test
    void requiresConfirmationForLowConfidenceModelCandidate() {
        MemoryCandidateDecisionService service = new MemoryCandidateDecisionService();

        String decision = service.decide("model_suggested", "normal", 0.62);

        assertThat(decision).isEqualTo("needs_user_confirmation");
    }

    @Test
    void autoSavesUserConfirmedMemory() {
        MemoryCandidateDecisionService service = new MemoryCandidateDecisionService();

        String decision = service.decide("user_confirmed", "normal", 0.2);

        assertThat(decision).isEqualTo("auto_saved");
    }
}
```

运行：

```powershell
cd backend
.\mvnw test -Dtest=ConversationPostProcessorTest,MemoryCandidateDecisionServiceTest
```

期望结果：测试通过。

---

## Task 11：Agent 会话、消息和 Memory 召回

**Files:**

- Create: `backend/src/main/java/ai/chrono/backend/agent/AgentController.java`
- Create: `backend/src/main/java/ai/chrono/backend/agent/AgentService.java`
- Create: `backend/src/main/java/ai/chrono/backend/memory/MemoryService.java`
- Test: `backend/src/test/java/ai/chrono/backend/agent/AgentControllerTest.java`
- Test: `backend/src/test/java/ai/chrono/backend/memory/MemoryServiceTest.java`

- [ ] **Step 1：定义 Agent 消息请求和响应**

```java
package ai.chrono.backend.agent;

import jakarta.validation.constraints.NotBlank;

public record AgentMessageRequest(
        @NotBlank String userId,
        @NotBlank String content
) {
}
```

```java
package ai.chrono.backend.agent;

public record AgentMessageResponse(
        String conversationSessionId,
        String userMessageId,
        String assistantMessageId,
        String runId,
        String content,
        String safetyLevel
) {
}
```

- [ ] **Step 2：实现短期上下文对象**

```java
package ai.chrono.backend.memory;

import java.util.List;

public record AgentContext(
        String userId,
        List<ContextItem> items
) {
    public record ContextItem(
            String sourceType,
            String sourceId,
            String content,
            String reason,
            double score
    ) {
    }
}
```

- [ ] **Step 3：实现 MemoryService 最小召回策略**

```java
package ai.chrono.backend.memory;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MemoryService {
    public AgentContext buildContext(String userId, String userMessage) {
        AgentContext.ContextItem currentMessage = new AgentContext.ContextItem(
                "current_message",
                "inline",
                userMessage,
                "当前用户消息必须进入短期上下文",
                1.0
        );
        return new AgentContext(userId, List.of(currentMessage));
    }
}
```

- [ ] **Step 4：测试短期上下文**

```java
package ai.chrono.backend.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryServiceTest {
    @Test
    void includesCurrentMessageInShortTermContext() {
        MemoryService service = new MemoryService();

        AgentContext context = service.buildContext("user-1", "我今天有点累");

        assertThat(context.userId()).isEqualTo("user-1");
        assertThat(context.items()).hasSize(1);
        assertThat(context.items().getFirst().sourceType()).isEqualTo("current_message");
    }
}
```

- [ ] **Step 5：实现 AgentService**

```java
package ai.chrono.backend.agent;

import ai.chrono.backend.memory.AgentContext;
import ai.chrono.backend.memory.MemoryService;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AgentService {
    private final MemoryService memoryService;

    public AgentService(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    public AgentMessageResponse reply(AgentMessageRequest request) {
        AgentContext context = memoryService.buildContext(request.userId(), request.content());
        String assistantText = "我会先帮你做一个温和复盘：你刚才提到的内容可以和最近睡眠、压力和互动情况一起看。";
        return new AgentMessageResponse(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                assistantText,
                context.items().isEmpty() ? "normal" : "normal"
        );
    }
}
```

- [ ] **Step 6：实现 Controller 和测试**

Controller：

```java
package ai.chrono.backend.agent;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping("/check-in")
    ResponseEntity<AgentMessageResponse> checkIn(@Valid @RequestBody AgentMessageRequest request) {
        return ResponseEntity.ok(agentService.reply(request));
    }
}
```

测试：

```java
package ai.chrono.backend.agent;

import ai.chrono.backend.memory.MemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentController.class)
@Import({AgentService.class, MemoryService.class})
class AgentControllerTest {
    @Autowired
    MockMvc mockMvc;

    @Test
    void checkInReturnsSupportiveResponse() throws Exception {
        String json = """
                {
                  "userId": "user-1",
                  "content": "我今天感觉很累"
                }
                """;

        mockMvc.perform(post("/api/agent/check-in").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.safetyLevel").value("normal"))
                .andExpect(jsonPath("$.content").isNotEmpty());
    }
}
```

运行：

```powershell
cd backend
.\mvnw test -Dtest=MemoryServiceTest,AgentControllerTest
```

期望结果：测试通过。

---

## Task 12：声纹聚类、标签建议和人物洞察

**Files:**

- Create: `backend/src/main/java/ai/chrono/backend/speaker/SpeakerClusteringService.java`
- Create: `backend/src/main/java/ai/chrono/backend/speaker/SpeakerLabelSuggestionService.java`
- Create: `backend/src/main/java/ai/chrono/backend/speaker/PersonInsightService.java`
- Test: `backend/src/test/java/ai/chrono/backend/speaker/SpeakerClusteringServiceTest.java`
- Test: `backend/src/test/java/ai/chrono/backend/speaker/SpeakerLabelSuggestionServiceTest.java`

- [ ] **Step 1：实现聚类决策**

```java
package ai.chrono.backend.speaker;

import org.springframework.stereotype.Service;

@Service
public class SpeakerClusteringService {
    private static final double HIGH_THRESHOLD = 0.82;
    private static final double LOW_THRESHOLD = 0.70;

    public String decide(double similarity) {
        if (similarity >= HIGH_THRESHOLD) {
            return "match_existing";
        }
        if (similarity >= LOW_THRESHOLD) {
            return "needs_user_confirmation";
        }
        return "create_unknown_cluster";
    }
}
```

- [ ] **Step 2：测试聚类决策**

```java
package ai.chrono.backend.speaker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpeakerClusteringServiceTest {
    @Test
    void matchesExistingClusterWhenSimilarityIsHigh() {
        SpeakerClusteringService service = new SpeakerClusteringService();

        assertThat(service.decide(0.9)).isEqualTo("match_existing");
    }

    @Test
    void createsUnknownClusterWhenSimilarityIsLow() {
        SpeakerClusteringService service = new SpeakerClusteringService();

        assertThat(service.decide(0.4)).isEqualTo("create_unknown_cluster");
    }
}
```

- [ ] **Step 3：实现文本标签建议**

```java
package ai.chrono.backend.speaker;

import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SpeakerLabelSuggestionService {
    private static final Pattern CHINESE_SELF_INTRO =
            Pattern.compile("(我是|我叫|我的名字是)\\s*([\\u4e00-\\u9fa5A-Za-z0-9_]{2,20})");

    public Optional<String> suggestFromTranscript(String transcript) {
        Matcher matcher = CHINESE_SELF_INTRO.matcher(transcript);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(matcher.group(2));
    }
}
```

- [ ] **Step 4：测试标签建议**

```java
package ai.chrono.backend.speaker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpeakerLabelSuggestionServiceTest {
    @Test
    void suggestsLabelFromChineseSelfIntroduction() {
        SpeakerLabelSuggestionService service = new SpeakerLabelSuggestionService();

        assertThat(service.suggestFromTranscript("你好，我叫张三，今天第一次见。"))
                .contains("张三");
    }

    @Test
    void doesNotSuggestWhenNoSelfIntroductionExists() {
        SpeakerLabelSuggestionService service = new SpeakerLabelSuggestionService();

        assertThat(service.suggestFromTranscript("今天会议有点紧张。"))
                .isEmpty();
    }
}
```

- [ ] **Step 5：实现人物洞察文案边界**

```java
package ai.chrono.backend.speaker;

import org.springframework.stereotype.Service;

@Service
public class PersonInsightService {
    public String summarizeInteraction(String displayName, int interactionCount) {
        String name = displayName == null || displayName.isBlank() ? "这个人" : displayName;
        return name + "最近出现 " + interactionCount + " 次。这个统计只能作为互动复盘线索，不能代表真实身份或关系判断。";
    }
}
```

运行：

```powershell
cd backend
.\mvnw test -Dtest=SpeakerClusteringServiceTest,SpeakerLabelSuggestionServiceTest
```

期望结果：测试通过。

---

## Task 13：安全、隐私、审计和删除策略

**Files:**

- Create: `backend/src/main/java/ai/chrono/backend/common/LogSanitizer.java`
- Create: `backend/src/main/java/ai/chrono/backend/safety/AgentSafetyGuard.java`
- Create: `backend/src/main/java/ai/chrono/backend/audit/AuditEvent.java`
- Test: `backend/src/test/java/ai/chrono/backend/common/LogSanitizerTest.java`
- Test: `backend/src/test/java/ai/chrono/backend/safety/AgentSafetyGuardTest.java`

- [ ] **Step 1：实现日志脱敏**

```java
package ai.chrono.backend.common;

public final class LogSanitizer {
    private LogSanitizer() {
    }

    public static String summarize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 24) {
            return normalized;
        }
        return normalized.substring(0, 24) + "...";
    }
}
```

- [ ] **Step 2：测试日志脱敏**

```java
package ai.chrono.backend.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogSanitizerTest {
    @Test
    void truncatesLongSensitiveText() {
        String result = LogSanitizer.summarize("这是一段很长的用户心理状态描述，不应该完整进入日志。");

        assertThat(result).endsWith("...");
        assertThat(result.length()).isLessThan(30);
    }
}
```

- [ ] **Step 3：实现 Agent 安全护栏**

```java
package ai.chrono.backend.safety;

public class AgentSafetyGuard {
    private final int maxRecallItems;
    private final int maxToolCalls;

    public AgentSafetyGuard(int maxRecallItems, int maxToolCalls) {
        this.maxRecallItems = maxRecallItems;
        this.maxToolCalls = maxToolCalls;
    }

    public void validate(int recallItems, int toolCalls) {
        if (recallItems > maxRecallItems) {
            throw new IllegalArgumentException("recall item limit exceeded");
        }
        if (toolCalls > maxToolCalls) {
            throw new IllegalArgumentException("tool call limit exceeded");
        }
    }
}
```

- [ ] **Step 4：测试安全护栏**

```java
package ai.chrono.backend.safety;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentSafetyGuardTest {
    @Test
    void rejectsTooManyRecallItems() {
        AgentSafetyGuard guard = new AgentSafetyGuard(10, 5);

        assertThatThrownBy(() -> guard.validate(11, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("recall item limit exceeded");
    }
}
```

- [ ] **Step 5：定义审计事件对象**

```java
package ai.chrono.backend.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEvent(
        UUID id,
        String userId,
        String actorType,
        String action,
        String targetType,
        UUID targetId,
        Map<String, Object> metadata,
        Instant createdAt
) {
    public static AuditEvent userAction(String userId, String action, String targetType, UUID targetId) {
        return new AuditEvent(
                UUID.randomUUID(),
                userId,
                "user",
                action,
                targetType,
                targetId,
                Map.of(),
                Instant.now()
        );
    }
}
```

运行：

```powershell
cd backend
.\mvnw test -Dtest=LogSanitizerTest,AgentSafetyGuardTest
```

期望结果：测试通过。

---

## Task 14：端到端 MVP 验证

**Files:**

- Create: `scripts/verify-mvp.ps1`
- Modify: `README.md`

- [ ] **Step 1：创建验证脚本**

`scripts/verify-mvp.ps1`：

```powershell
$ErrorActionPreference = "Stop"

Write-Host "Checking PostgreSQL container..."
docker compose up -d postgres

Write-Host "Running backend tests..."
Push-Location backend
.\mvnw test
Pop-Location

Write-Host "Running model service tests..."
Push-Location model-service
.\.venv\Scripts\python -m pytest
Pop-Location

Write-Host "MVP verification completed."
```

- [ ] **Step 2：运行完整验证**

命令：

```powershell
.\scripts\verify-mvp.ps1
```

期望结果：

- PostgreSQL 启动。
- Java 测试通过。
- Python 测试通过。
- 输出 `MVP verification completed.`。

- [ ] **Step 3：更新 README 验证说明**

追加内容：

````markdown
## MVP 验证

运行：

```powershell
.\scripts\verify-mvp.ps1
```

该脚本会启动 PostgreSQL，运行 Java 后端测试和 Python 模型服务测试。
````

---

## Task 15：本地全流程 Demo 增量实现

**Status:** 已落地。

**Files:**

- Create/Modify: `backend/src/main/resources/static/index.html`
- Create/Modify: `backend/src/main/resources/static/styles.css`
- Create/Modify: `backend/src/main/resources/static/app.js`
- Create/Modify: `backend/src/main/java/ai/chrono/backend/demo/*`
- Create/Modify: `backend/src/main/java/ai/chrono/backend/websocket/*`
- Modify: `backend/src/main/java/ai/chrono/backend/modelclient/*`
- Modify: `backend/pom.xml`
- Modify: `README.md`
- Create/Modify: `docs/superpowers/specs/2026-06-11-chrono-agent-demo-flow-design.md`

- [x] **Step 1：实现本地 Demo 工作台**

工作台入口：

```text
http://localhost:8080/
```

页面需要支持：

- 选择或输入 `userId`。
- 上传录音文件。
- 使用浏览器麦克风录音后上传。
- 使用 WebSocket 实时发送音频片段。
- 录入健康事件。
- 展示时间线、会话摘要和转写片段。
- 展示周围人物、用户标注、候选记忆和长期记忆。
- 展示 Agent 对话和本轮召回上下文。
- 展示已落库的音频、健康、会话、人物、转写、洞察、候选记忆、长期记忆、Agent 会话、消息、run、召回、模型任务和审计日志。

- [x] **Step 2：实现 Demo Pipeline**

`DemoPipelineService` 负责把演示操作写入真实数据库表，而不是只展示静态假数据。

音频上传或 WebSocket stop 后写入：

- `audio_event`
- `model_job`
- `speaker_cluster`
- `speaker_segment`
- `speaker_embedding`
- `conversation_memory`
- `memory_write_candidate`
- `person_insight`
- `audit_log`

Agent 对话写入：

- `conversation_session`
- `agent_message`
- `agent_run`
- `memory_recall_event`
- `memory_write_candidate`

召回索引写入：

- `conversation_memory` 写入后尝试 upsert 到 DashVector。
- `memory_item` 写入后尝试 upsert 到 DashVector。
- `health_event` 写入后尝试 upsert 到 DashVector。
- `person_insight` 写入后尝试 upsert 到 DashVector。
- 索引失败写 `audit_log`，不回滚 PostgreSQL 主数据。

- [x] **Step 3：实现 Spring WebSocket 音频流**

实现约定：

- 使用 Spring WebSocket `WebSocketConfigurer` 注册 `/ws/audio`。
- 使用 `AudioStreamWebSocketHandler` 接收二进制音频片段和 `stop` 控制消息。
- WebSocket 建连时创建 `audio_stream_session`。
- 收到音频片段后更新 `last_active_at`。
- 收到 `stop` 后合并片段，复用 `DemoPipelineService.processAudio(...)`。
- 处理完成后返回 `processing_completed`，并把 `audio_event.stream_session_id` 关联到本次流式会话。

服务端消息类型：

- `stream_opened`
- `chunk_received`
- `processing_started`
- `processing_completed`
- `error`

- [x] **Step 4：模型服务调用改为 RestTemplate + fastjson2**

实现约定：

- `ModelServiceClient` 使用 `RestTemplate.exchange(...)` 调 Python FastAPI。
- 请求体使用 `fastjson2` 序列化为 JSON 字符串。
- 响应体使用 `fastjson2` 反序列化为 Java record。
- Java DTO 使用 `@JSONField` 映射 snake_case 字段。
- 不依赖 `spring-boot-starter-json` 作为 Java 调 Python 的消息转换器。
- Agent 回复调用 Python `/v1/agent/reply`，由 OpenRouter NVIDIA Nemotron 3 Nano Omni 生成。
- Agent 召回调用 Python `/v1/vector/search`，由 OpenRouter embeddings + DashVector 返回上下文。
- 如果 OpenRouter 或 DashVector 不可用，本轮 Agent 对话返回失败，不生成固定模板助手回复。

- [x] **Step 5：补齐 Demo 展示细节**

实现约定：

- `/api/demo/state` 聚合所有演示需要的数据。
- PostgreSQL `jsonb` 字段返回前转换成普通 JSON 数组或对象。
- 浏览器麦克风录音前检查 `navigator.mediaDevices.getUserMedia`。
- 非安全上下文或局域网 HTTP 访问导致麦克风不可用时，前端展示明确提示。

- [x] **Step 6：验证**

验证命令：

```powershell
.\scripts\verify-mvp.ps1
```

额外手动验证：

- `POST /api/demo/audio` 可以上传录音并生成会话记录。
- `POST /api/demo/health` 可以写入健康事件。
- `PATCH /api/demo/speakers/{speakerClusterId}/label` 可以标注匿名人物。
- `POST /api/demo/memory-candidates/{candidateId}/accept` 可以生成长期记忆。
- 配置 OpenRouter 和 DashVector 后，`POST /api/demo/agent/messages` 可以生成 Agent 回复、消息、run 和召回事件。
- `WS /ws/audio?userId=` 可以完成 `stream_opened`、`chunk_received`、`processing_started`、`processing_completed`。
- 浏览器打开 `http://localhost:8080/` 后可以展示全流程和已存储用户数据。

## 4. 验收清单

- [ ] 可以上传音频并生成 `audio_event`。
- [ ] 可以创建 `audio_stream_session` 并限制单用户单活跃流。
- [ ] 可以创建 `model_job` 并追踪任务状态。
- [ ] Python fake 音频模型服务可以返回转写、说话人片段、声纹样本引用、摘要、候选记忆和安全结果。
- [ ] Agent 回复使用 OpenRouter NVIDIA Nemotron 3 Nano Omni，LLM 不可用时返回失败。
- [ ] Agent 召回使用 OpenRouter embeddings + 阿里云 DashVector，DashVector 不可用时本轮对话返回失败。
- [ ] Java 可以保存 `conversation_memory`，并对低价值音频设置 `discarded`。
- [ ] 可以写入健康事件，并在时间线和 Agent 上下文中被引用。
- [ ] 可以创建 Agent 会话、用户消息、助手消息和 Agent run。
- [ ] 可以从会话记录和消息中生成候选个人记忆。
- [ ] 可以召回会话记录、个人记忆、人物洞察和健康事件。
- [ ] 可以创建匿名说话人聚类。
- [ ] 可以保存声纹样本引用，但不跨账号匹配。
- [ ] 可以生成说话人标签建议，用户确认后才更新显示名。
- [ ] 可以记录人物标注历史。
- [ ] 可以生成人物洞察，并避免真实身份和敏感属性推断。
- [ ] 可以记录敏感操作审计日志。
- [ ] 日志不会输出完整转写、声纹向量、完整 prompt 或完整心理状态描述。
- [ ] 本地 Demo 可以上传录音文件并展示处理结果。
- [ ] 本地 Demo 可以使用浏览器麦克风录音后上传。
- [ ] 本地 Demo 可以通过 `/ws/audio` 演示 WebSocket 音频片段流。
- [ ] 本地 Demo 可以展示已存储用户数据，而不是静态假数据。
- [ ] Java 调 Python 模型服务使用 `RestTemplate` + `fastjson2`，并保持 snake_case JSON 合约。

## 5. 自检结果

本计划已经按中文技术方案重新覆盖：

- 架构边界：Task 2、Task 3、Task 4。
- 数据库设计：Task 5。
- 音频上传和流式接入：Task 6、Task 8。
- 模型任务和 Python fake 模型流水线：Task 6、Task 7。
- 健康数据和时间线：Task 9。
- 会话记录和后处理：Task 10。
- Agent 会话、消息、短期上下文和个人记忆：Task 11。
- 声纹识别、未注册人物分析和用户标注：Task 12。
- 安全、隐私、审计和删除策略基础：Task 13。
- 端到端验证：Task 14。
- 本地浏览器 Demo、Spring WebSocket、已存储用户数据展示和 `RestTemplate` + `fastjson2` 模型调用：Task 15。

明确不进入 MVP：

- 外部数据接入。
- 插件市场。
- 独立任务系统。
- 自建或本地部署独立向量数据库；当前召回索引使用阿里云 DashVector。
- 跨账号声纹匹配。
- 医疗诊断和心理治疗。

## 6. 执行交接

计划已完成并保存到 `docs/superpowers/plans/2026-06-11-chrono-agent-mvp.md`。有两种执行方式：

1. **Subagent-Driven，推荐**：每个 Task 使用独立执行单元实现，任务之间做检查点 review，适合减少上下文污染。
2. **Inline Execution**：在当前线程按 Task 顺序实现，适合持续讨论和随时调整。

请选择执行方式。

当前项目已按 Task 15 补齐本地演示层。后续继续执行计划时，应以现有代码为准，不要回退 WebSocket、模型调用和 Demo 数据展示实现。
