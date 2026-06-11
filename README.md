# Chrono Agent

Chrono Agent 是面向智能项链场景的个人 Agent MVP。

## 模块

- `backend`：Java Spring Boot 后端，负责 API、持久化、Agent 编排和审计。
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

## 本地 Demo

Demo 使用 Java 后端静态页面展示完整流程，Python 模型服务仍使用 fake provider。

1. 启动 PostgreSQL：

   ```powershell
   docker compose up -d postgres
   ```

2. 启动 Python 模型服务：

   ```powershell
   cd model-service
   .\.venv\Scripts\python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
   ```

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
- WebSocket 音频流 `/ws/audio?userId=demo-user`，停止后合并处理。
- 录入健康事件。
- 展示已存储的音频、会话、转写、人物、洞察、候选记忆、长期记忆、Agent 消息、召回事件、模型任务和审计日志。
- 标注匿名人物。
- 接受或拒绝候选记忆。
- Agent 对话并展示本轮召回上下文。

## MVP 验证

运行：

```powershell
.\scripts\verify-mvp.ps1
```

该脚本会启动 PostgreSQL，运行 Java 后端测试和 Python 模型服务测试。
