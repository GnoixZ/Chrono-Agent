# Chrono Agent

Chrono Agent 是面向智能项链场景的个人 Agent MVP。

## 模块

- `backend`：Java Spring Boot 后端，负责 API、静态 Demo、WebSocket 音频流、持久化、Agent 编排和审计。
- `model-service`：Python FastAPI 模型服务，负责音频、声纹、摘要、候选记忆和回复生成。
- `docker-compose.yml`：本地 PostgreSQL。

## 本地开发

运行 Java 后端前，确保 `JAVA_HOME` 指向 JDK 21 或更高版本。当前验证脚本会在本机检测到 `E:\OpenSources\jdk23` 时自动使用它。

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

## 关键文档

- 设计规格：`docs/superpowers/specs/2026-06-11-chrono-agent-design.md`
- 技术方案：`docs/superpowers/specs/2026-06-11-chrono-agent-technical-solution.md`
- Demo 设计：`docs/superpowers/specs/2026-06-11-chrono-agent-demo-flow-design.md`
- 实施计划：`docs/superpowers/plans/2026-06-11-chrono-agent-mvp.md`

## 本地 Demo

Demo 使用 Java 后端静态页面展示完整流程。音频分析仍使用 fake provider；Agent 回复使用 OpenRouter 上的 NVIDIA Nemotron 3 Nano Omni；Agent 召回使用 OpenRouter NVIDIA Llama Nemotron Embed VL + 阿里云 DashVector。

1. 启动 PostgreSQL：

   ```powershell
   docker compose up -d postgres
   ```

2. 启动 Python 模型服务：

   ```powershell
   cd model-service
   .\.venv\Scripts\pip install -e .
   $env:CHRONO_OPENROUTER_API_KEY="<your-openrouter-api-key>"
   $env:CHRONO_DASHVECTOR_API_KEY="<your-dashvector-api-key>"
   $env:CHRONO_DASHVECTOR_ENDPOINT="<your-dashvector-cluster-endpoint>"
   $env:CHRONO_DASHVECTOR_COLLECTION="chrono_agent_memory"
   .\.venv\Scripts\python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
   ```

   默认模型和索引配置：

   - `CHRONO_OPENROUTER_MODEL`：默认 `nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free`。
   - `CHRONO_OPENROUTER_EMBEDDING_MODEL`：默认 `nvidia/llama-nemotron-embed-vl-1b-v2:free`。
   - `CHRONO_EMBEDDING_DIMENSION`：默认 `2048`，DashVector collection 的向量维度必须与它一致。
   - `CHRONO_DASHVECTOR_COLLECTION`：默认 `chrono_agent_memory`，需要提前在 DashVector 创建。
   - 如果 OpenRouter 或 DashVector 不可用，Agent 对话会返回失败，不生成假的助手回复。

3. 启动 Java 后端：

   ```powershell
   cd backend
   $env:JAVA_HOME="E:\OpenSources\jdk23"
   $env:PATH="$env:JAVA_HOME\bin;$env:PATH"
   .\mvnw spring-boot:run
   ```

4. 打开浏览器：

   ```text
   http://localhost:8080/
   ```

Demo 支持：

- 上传录音文件并生成会话记录。
- 浏览器麦克风录音后上传。
- WebSocket 音频流 `/ws/audio?userId=demo-user`，浏览器按片段发送音频块，停止后合并处理。
- 录入健康事件。
- 展示已存储的音频、会话、转写、人物、洞察、候选记忆、长期记忆、Agent 消息、召回事件、模型任务和审计日志。
- 标注匿名人物。
- 接受或拒绝候选记忆。
- Agent 对话，基于 DashVector 召回上下文，并展示本轮召回来源。

浏览器录音注意事项：

- 麦克风录音和 WebSocket 流式录音需要浏览器允许麦克风权限。
- 本机演示建议使用 `http://localhost:8080/` 或 `http://127.0.0.1:8080/`。
- 如果通过局域网 HTTP 地址访问页面，浏览器通常不会开放麦克风能力，需要改用 HTTPS 或回到本机 localhost。

当前实现说明：

- Java 调 Python 模型服务使用 `RestTemplate` 发送 HTTP JSON 请求。
- Java/Python 模型接口字段使用 snake_case，Java DTO 通过 `fastjson2` 的 `@JSONField` 做序列化映射。
- Agent 回复由 Python 调用 OpenRouter chat completions，当前默认模型为 NVIDIA Nemotron 3 Nano Omni。
- 召回索引由 Python 调用 OpenRouter embeddings 生成文本向量，并写入/查询阿里云 DashVector。
- WebSocket 使用 Spring WebSocket `WebSocketHandler` 注册 `/ws/audio`。
- Demo 状态接口会把 PostgreSQL `jsonb` 字段转换成普通 JSON，方便前端“已存储用户数据”面板展示。

## MVP 验证

运行：

```powershell
.\scripts\verify-mvp.ps1
```

该脚本会启动 PostgreSQL，运行 Java 后端测试和 Python 模型服务测试。
