# 计划：目标流程 vs 现状 vs 改造项可执行清单

## 目标

把 Chrono Agent 当前 Demo 与后端实现，从“手动开始流式录音 + 30 秒窗口异步分析 + 窗口级会话记录”，收敛到以下目标流程：

1. 健康数据作为时序数据直接落库。
2. Demo 以实时音频流为主入口。
3. 本地检测到连续人声后开始一次会话，而不是仅凭用户点击开始。
4. 音频在会话进行中实时送往模型层做对话转写。
5. 连续静音 60 秒后结束当前会话。
6. 会话结束后异步做 ASR、说话人识别、摘要、Memory 与会话后处理。
7. 后处理阶段支持 conversation 合并 / 拆分。
8. 用户最终通过 Agent 基于沉淀后的上下文继续对话。

## 非目标

- 不在这一轮引入外部消息队列、Kafka 或独立流处理系统。
- 不引入新的数据库类型或自建向量数据库。
- 不直接切换到真实硬件或移动端后台录音方案。
- 不在第一步就做生产级低延迟字幕体验。
- 不在未明确收益前引入计划外依赖。

## 约束

- Java 后端继续作为唯一可信状态源。
- Python 模型服务继续保持无状态，不直接写 PostgreSQL。
- 继续沿用 Spring Boot、Spring WebSocket、FastAPI、PostgreSQL、Flyway、`RestTemplate`、`fastjson2`。
- 音频仍先保存到本地文件系统。
- 所有查询与写入继续带 `userId` 隔离。
- 继续遵守当前匿名人物、声纹和记忆写入的隐私边界。
- 改造应优先复用既有 `DemoPipelineService`、`modelclient` 和向量召回适配层。

## 输入文档

- 已参考 [AGENTS.md](file:///i:/Chrono%20Agent/AGENTS.md)
- 已参考 [2026-06-11-chrono-agent-design.md](file:///i:/Chrono%20Agent/docs/superpowers/specs/2026-06-11-chrono-agent-design.md)
- 已参考 [2026-06-11-chrono-agent-technical-solution.md](file:///i:/Chrono%20Agent/docs/superpowers/specs/2026-06-11-chrono-agent-technical-solution.md)
- 已参考 [2026-06-11-chrono-agent-demo-flow-design.md](file:///i:/Chrono%20Agent/docs/superpowers/specs/2026-06-11-chrono-agent-demo-flow-design.md)
- 已参考 [2026-06-11-long-stream-audio-windowing-plan.md](file:///i:/Chrono%20Agent/docs/superpowers/plans/2026-06-11-long-stream-audio-windowing-plan.md)
- 未找到 `docs/PROJECT_STATUS.md`
- 未找到 `docs/DECISIONS.md`

## 涉及文件或模块

- `backend/src/main/resources/static/index.html`
- `backend/src/main/resources/static/app.js`
- `backend/src/main/resources/static/styles.css`
- `backend/src/main/java/ai/chrono/backend/websocket/AudioStreamWebSocketHandler.java`
- `backend/src/main/java/ai/chrono/backend/demo/DemoPipelineService.java`
- `backend/src/main/java/ai/chrono/backend/task/AudioAnalyzeTaskService.java`
- `backend/src/main/java/ai/chrono/backend/health/HealthController.java`
- `backend/src/main/java/ai/chrono/backend/audio/*`
- `backend/src/main/java/ai/chrono/backend/agent/*`
- `backend/src/main/java/ai/chrono/backend/conversation/*`
- `backend/src/main/resources/db/migration/*`
- `backend/src/main/java/ai/chrono/backend/modelclient/*`
- `model-service/app/main.py`
- `model-service/app/services/analyze_audio.py`
- `model-service/app/services/generate_reply.py`
- `model-service/app/providers/fake.py`
- `model-service/app/schemas.py`

## 目标流程 vs 现状 vs 改造项

| 主题 | 目标流程 | 当前现状 | 主要改造项 |
| --- | --- | --- | --- |
| 健康数据 | 作为时序数据直接落库，模型按需实时计算或召回 | Demo 已直落 `health_event`，正式 `/api/health/events` 仍未真正持久化 | 统一 Demo API 与正式 API，补齐 `POST/GET /api/health/events` 的真实持久化与查询 |
| 会话触发 | 本地连续人声触发会话开始 | 当前靠用户点击开始后立即持续采集和发送 | 在前端增加本地 VAD 状态机，区分“连接已建立”和“会话已开始” |
| 实时转写 | 会话中增量发送音频并返回增量转写 | 当前只做窗口级批处理，没有增量 transcript | 在模型服务与 Java 适配层补流式或准流式 transcript 接口与事件 |
| 会话结束 | 连续静音 60 秒自动结束 | 当前依赖手动 `stop` 或断连，窗口阈值为 30 秒 | 在前端 VAD 或服务端状态机中加入 `60s` 静音结束规则 |
| 后处理粒度 | 以“整场会话”为单位异步做 ASR、speaker、summary、memory | 当前每个窗口直接产出一条 `conversation_memory` | 引入 `stream session -> window -> session summary` 的两级处理链路 |
| 会话记录 | 后处理后可对多个窗口合并 / 拆分 conversation | 当前无 conversation merge / split，仅有窗口级记录 | 增加 conversation 聚合规则、重算入口与审计 |
| Agent 对话 | 基于会话记录、健康数据、memory、人物洞察做正式对话 | Demo 版已具备持久化链路，正式 Agent API 仍偏 stub | 统一 Agent 入口，复用 Demo 持久化链路并去掉双轨实现 |
| Demo 体验 | 以“实时流 -> 会话 -> 后处理 -> Agent”串起完整流程 | 当前更像“录音窗口处理演示 + 数据总览” | 重排页面主流程与状态文案，让用户先看到会话生命周期 |

## 风险

- **状态机会变复杂**：前端 VAD、本地静音计时、服务端 stream session 与会话聚合会同时存在。
- **接口边界要重新切分**：现在批量 `/v1/audio/analyze` 可能不足以承载增量 transcript。
- **窗口级与会话级结果可能重复**：如果不补中间层，会出现窗口摘要与整场会话摘要并存冲突。
- **静音阈值需要调优**：`60 秒` 对 Demo 友好，但对不同环境噪声敏感。
- **Demo 与正式 API 双轨成本高**：如果继续同时维护 `/api/demo/*` 与正式 API，会放大后续维护成本。

## 推荐落地顺序

### Task 1：统一健康数据写入与查询入口

目标：
让健康数据的“时序直落库”从 Demo 路径扩展为正式 API 能力，并统一到只负责主数据落库与查询的健康事件链路。

涉及文件：
- `backend/src/main/java/ai/chrono/backend/health/HealthController.java`
- `backend/src/main/java/ai/chrono/backend/demo/DemoPipelineService.java`
- `backend/src/main/resources/db/migration/*`
- 可能新增或补充 `health` 领域 service / repository

步骤：
1. 把 Demo 中真实写库逻辑从 `DemoPipelineService` 抽到正式 `health` 领域 service。
2. 为 `/api/health/events` 补齐真实写库能力。
3. 为 `/api/health/events` 补齐按时间与类型查询能力。
4. 让 Demo 页面优先调用正式健康 API，而不是 `/api/demo/health`。
5. 保持 Task 1 范围只覆盖主数据直落库与查询，不再包含向量索引写入。

验收标准：
- 健康事件可通过正式 API 真实写入 `health_event`。
- 健康事件可按 `userId`、时间范围、类型查询。
- Task 1 完成后不会额外触发健康数据向量写入逻辑。
- Demo 页面写入后的数据在正式查询接口中可见。

验证方式：
- `cd backend; .\mvnw test`
- 手工调用 `POST /api/health/events` 与 `GET /api/health/events`
- 页面写入健康事件后刷新数据视图确认落库
- 确认本次链路没有健康数据向量写入行为

建议 commit：
- feat: unify health event persistence and query flow

### Task 2：把实时音频入口改为“连接态”和“会话态”分离

目标：
让前端与后端区分 WebSocket 已连接、正在监听、会话已开始、会话已结束，而不是“连接即会话开始”。

涉及文件：
- `backend/src/main/resources/static/app.js`
- `backend/src/main/resources/static/index.html`
- `backend/src/main/resources/static/styles.css`
- `backend/src/main/java/ai/chrono/backend/websocket/AudioStreamWebSocketHandler.java`

步骤：
1. 为前端录音状态新增 `idle/listening/in_session/post_processing` 等阶段。
2. 建立本地音量或 VAD 判定逻辑，检测连续人声。
3. 建立 WebSocket 后先进入监听态，不立即标记为会话开始。
4. 检测到连续人声后向服务端发送 `session_started` 控制事件。
5. 服务端把 stream session 与 conversation session 的状态消息区分开返回。

验收标准：
- 用户点击开始后先进入监听态，而非直接开始业务会话。
- 只有满足连续人声条件后，页面才显示“会话开始”。
- 后端可观察到会话开始时间与连接开始时间不同。

验证方式：
- 手工打开 Demo 页面做麦克风测试
- 观察状态区是否出现“监听中 -> 会话开始”切换
- 查看数据库或状态接口确认时间戳分离

建议 commit：
- feat: separate websocket listening state from conversation state

### Task 3：补本地静音 60 秒结束会话状态机

目标：
把“手动 stop”改为“本地长静音自动结束会话”，同时保留手动 stop 作为兜底控制。

涉及文件：
- `backend/src/main/resources/static/app.js`
- `backend/src/main/java/ai/chrono/backend/websocket/AudioStreamWebSocketHandler.java`
- 可能补充 WebSocket 消息 DTO 或常量

步骤：
1. 在前端 VAD 状态机中增加静音计时器。
2. 当会话已开始后，累计静音达到 `60 秒` 自动发送结束控制消息。
3. 手动 stop 仍保留，但语义改为“强制结束当前会话”。
4. 后端记录自动结束原因，如 `silence_timeout_60s`。
5. 页面上展示倒计时或静音累计状态，便于用户理解为什么会结束。

验收标准：
- 会话开始后，持续静音 60 秒会自动结束。
- 自动结束和手动结束都能被后端区分。
- 不会因为短暂停顿误结束会话。

验证方式：
- 手工录入一段语音后保持静音 60 秒
- 确认前端自动结束且服务端记录正确关闭原因
- 回归测试手动停止路径仍可用

建议 commit：
- feat: auto close conversation after 60 seconds of silence

### Task 4：为模型层补“增量转写”接口与 Java 适配

目标：
让实时流在会话中能够返回增量 transcript，而不是只在窗口完成后得到整批分析结果。

涉及文件：
- `model-service/app/main.py`
- `model-service/app/services/analyze_audio.py`
- `model-service/app/providers/fake.py`
- `model-service/app/schemas.py`
- `backend/src/main/java/ai/chrono/backend/modelclient/*`
- `backend/src/main/java/ai/chrono/backend/websocket/AudioStreamWebSocketHandler.java`

步骤：
1. 设计最小增量 transcript 请求与响应结构。
2. 在 Python 侧先用 fake provider 返回可重复验证的增量文本。
3. 在 Java `modelclient` 中补新接口与 DTO。
4. WebSocket 接收窗口后除异步分析外，还能推送增量 transcript 事件。
5. 前端状态区展示最近几条增量转写。

验收标准：
- 会话进行中能持续看到增量 transcript 事件。
- 现有批量 `/v1/audio/analyze` 仍可继续用于后处理。
- fake provider 下可稳定复现而不依赖真实 ASR。

验证方式：
- `cd model-service; .\.venv\Scripts\python -m pytest`
- `cd backend; .\mvnw test`
- 手工录音时观察页面 transcript 是否连续更新

建议 commit：
- feat: add incremental transcript contract for streaming audio

### Task 5：引入“窗口级结果 + 会话级后处理”的两级流水线

目标：
保留当前 30 秒窗口化优势，但把最终业务沉淀从“每窗一条 conversation”调整为“窗口中间结果 + 会话级最终结果”。

涉及文件：
- `backend/src/main/java/ai/chrono/backend/demo/DemoPipelineService.java`
- `backend/src/main/java/ai/chrono/backend/task/AudioAnalyzeTaskService.java`
- `backend/src/main/java/ai/chrono/backend/conversation/*`
- `backend/src/main/resources/db/migration/*`
- 可能补充新的 session 聚合 service

步骤：
1. 明确窗口产物与最终会话产物的边界。
2. 窗口级仍保存 `audio_event`、`speaker_segment` 等原子结果。
3. 会话结束后新增一次 session post-processing job。
4. 由该 job 汇总窗口 transcript、speaker 线索、summary、memory candidates。
5. 最终只把会话级摘要作为主要 `conversation_memory` 对外展示。

验收标准：
- 单场会话结束后能生成 1 条主 `conversation_memory`。
- 窗口级数据仍保留用于审计和重算。
- 页面默认看到的是会话级结果，而不是窗口级噪音。

验证方式：
- 手工录制超过一个窗口的会话
- 确认数据库中既有窗口级数据，也有会话级最终记录
- 检查页面只主展示会话级摘要

建议 commit：
- feat: add session-level post processing over stream windows

### Task 6：补 conversation 合并 / 拆分与重处理入口

目标：
支持对会话后处理结果进行修正，避免窗口边界或静音阈值导致 conversation 颗粒度不合理。

涉及文件：
- `backend/src/main/java/ai/chrono/backend/conversation/*`
- `backend/src/main/java/ai/chrono/backend/demo/DemoController.java`
- `backend/src/main/resources/db/migration/*`
- 可能扩展 Demo 页面操作入口

步骤：
1. 定义 conversation merge / split 的最小业务规则。
2. 提供重处理入口，用于重新聚合 transcript 与 memory candidates。
3. 对 merge / split 操作补审计日志。
4. 更新页面以便触发最小人工修正操作。
5. 确保重算不会破坏历史证据引用。

验收标准：
- 能把相邻低间隔会话合并为一条。
- 能把误合并的大会话拆成两条或多条。
- 相关 memory 和 evidence refs 可追溯。

验证方式：
- 构造两段相邻会话进行合并验证
- 构造一条过长会话进行拆分验证
- 检查审计日志与 evidence 引用完整性

建议 commit：
- feat: support conversation merge split and reprocess

### Task 7：统一 Demo Agent 与正式 Agent 对话链路

目标：
让用户在完成会话沉淀后，统一通过正式 Agent 会话接口继续对话，而不是长期依赖 `/api/demo/agent/messages`。

涉及文件：
- `backend/src/main/java/ai/chrono/backend/agent/*`
- `backend/src/main/java/ai/chrono/backend/demo/DemoPipelineService.java`
- `backend/src/main/resources/static/app.js`
- `model-service/app/services/generate_reply.py`

步骤：
1. 梳理 Demo Agent 已有持久化能力与正式 Agent stub 之间的差异。
2. 把正式 Agent API 补齐到会话、消息、run、召回事件持久化。
3. 让 Demo 页面改调正式 Agent 接口。
4. 统一错误处理，保持 LLM 不可用时返回失败。
5. 清理重复或仅演示用途的 Agent 入口。

验收标准：
- 页面 Agent 对话不再依赖 Demo 专用接口。
- 正式 Agent 路径可写 `conversation_session`、`agent_message`、`agent_run`、`memory_recall_event`。
- 当 LLM 不可用时返回失败，不保存固定模板助手回复。

验证方式：
- `cd backend; .\mvnw test`
- 手工发起一轮 Agent 对话，确认会话与 run 落库
- 模拟模型不可用，确认失败路径符合约束

建议 commit：
- feat: unify demo and production agent conversation pipeline

### Task 8：重排 Demo 页面主叙事，突出“会话生命周期”

目标：
把页面从“功能堆叠演示台”调整为“实时流 -> 会话 -> 后处理 -> Agent”的主流程页面。

涉及文件：
- `backend/src/main/resources/static/index.html`
- `backend/src/main/resources/static/app.js`
- `backend/src/main/resources/static/styles.css`

步骤：
1. 重新排列页面区块顺序。
2. 强化实时状态、静音计时、增量 transcript、后处理进度的展示。
3. 把底层数据表展示下沉为辅助视图。
4. 在会话完成后突出显示摘要、人物、记忆候选与 Agent 入口。
5. 为局域网 HTTP 麦克风限制保留提示。

验收标准：
- 用户第一次进入页面即可理解当前主流程。
- 会话状态、后处理状态、最终结果三者界限清晰。
- 原始数据视图仍可用，但不干扰主体验。

验证方式：
- 手工打开 Demo 页面走完一轮完整流程
- 检查页面是否能清晰表达会话生命周期
- 必要时通过本地预览人工验收

建议 commit：
- feat: redesign demo around conversation lifecycle

## 推荐先执行

优先推荐从 **Task 1 -> Task 2 -> Task 3 -> Task 5 -> Task 7** 开始，原因：

- Task 1 先把健康数据从 Demo 专用路径拉回正式能力。
- Task 2 和 Task 3 先把“目标流程”的入口与结束条件建立起来。
- Task 5 再把窗口级处理升级为会话级业务结果。
- Task 7 最后统一 Agent，避免过早改两遍接口。

## 执行建议

- 若希望最小风险推进，优先按单 Task 提交，每个 Task 独立验证。
- 若希望先拿到可感知 Demo，先做 Task 2 + Task 3 + Task 8。
- 若希望先打通后端正确性，先做 Task 1 + Task 5 + Task 7。

## 执行记录（2026-06-12）

### Task 1：统一健康数据写入与查询入口

状态：已完成。

记录：
- 已将正式 `/api/health/events` 接到真实 `HealthService` 持久化与查询链路。
- Demo 健康事件提交已改为调用正式健康 API。
- 健康链路保持只写主数据，不触发向量索引。

验证：
- `backend/src/test/java/ai/chrono/backend/health/HealthControllerTest.java`
- `cd backend; $env:JAVA_HOME='E:\OpenSources\jdk21'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\mvnw.cmd test`

### Task 2：把实时音频入口改为“连接态”和“会话态”分离

状态：已完成。

记录：
- WebSocket 建连后进入 `listening`，不再把连接自动视为业务会话。
- 前端本地 VAD 检测连续人声后才发送 `session_started`。
- 后端拒绝未 `session_started` 的二进制音频，数据库记录 `session_started_at`。

验证：
- `AudioStreamWebSocketHandlerTest.binaryChunkBeforeSessionStartIsRejected`
- `AudioStreamWebSocketHandlerTest.stopSplitsLongStreamIntoMultipleWindows`
- `node --check backend/src/main/resources/static/app.js`

### Task 3：补本地静音 60 秒结束会话状态机

状态：已完成。

记录：
- 前端在业务会话开始后持续统计静音时长，达到 60 秒自动发送 `reason=silence_timeout_60s` 的 stop 控制消息。
- 手动停止发送 `reason=manual_stop`，后端 close reason 可区分。
- 页面展示静音秒数、60 秒阈值和关闭原因。

验证：
- `AudioStreamWebSocketHandlerTest.automaticSilenceStopPersistsCloseReason`
- `node --check backend/src/main/resources/static/app.js`

### Task 4：为模型层补“增量转写”接口与 Java 适配

状态：已完成。

记录：
- Python 模型服务新增 `/v1/audio/transcript`，fake provider 返回稳定可测的增量文本。
- Java `ModelServiceClient` 新增 `incrementalTranscript` DTO 与调用。
- WebSocket 每个业务 chunk 后推送 `incremental_transcript` 事件，失败时只推送 `transcript_error`，不阻断后处理。
- 页面新增最近增量转写展示区。

验证：
- `model-service/tests/test_analyze_audio.py::test_incremental_transcript_returns_deterministic_chunk_text`
- `AudioStreamWebSocketHandlerTest.stopSplitsLongStreamIntoMultipleWindows`
- `cd model-service; .\.venv\Scripts\python -m pytest`
- `cd backend; $env:JAVA_HOME='E:\OpenSources\jdk21'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\mvnw.cmd test`

### Task 5：引入“窗口级结果 + 会话级后处理”的两级流水线

状态：已完成。

记录：
- 流式窗口处理只保留 `audio_event`、`speaker_segment`、模型任务和审计，不再把每个窗口写成主 `conversation_memory`。
- 流会话停止后异步执行 session post-processing，汇总窗口 transcript、speaker refs 和 evidence refs，写入 1 条 `source_type=audio_session` 主会话记录。
- `audio_stream_session` 回填最终 `session_conversation_memory_id` 和 `session_post_processing_status`。

验证：
- `AudioStreamWebSocketHandlerTest.stopSplitsLongStreamIntoMultipleWindows`
- `scripts/verify-long-stream-windowing.ps1` 已同步为新协议验证脚本。
- `cd backend; $env:JAVA_HOME='E:\OpenSources\jdk21'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\mvnw.cmd test`

### Task 6：补 conversation 合并 / 拆分与重处理入口

状态：已完成。

记录：
- 新增正式 `/api/conversations/merge`、`/api/conversations/{id}/split`、`/api/conversations/{id}/reprocess`。
- 合并 / 拆分创建新的 `conversation_memory`，旧记录只标记 `discarded`，不物理删除。
- `evidence_refs`、`correction_of_id` 和 `audit_log` 保留修正来源。
- Demo 时间线新增合并最近两条、拆分、重算操作。

验证：
- `ConversationCorrectionServiceTest.mergeCreatesDerivedConversationAndDiscardsSources`
- `ConversationCorrectionServiceTest.splitCreatesPartsAndKeepsSourceTraceable`
- `ConversationCorrectionServiceTest.reprocessIncrementsAttempts`
- `cd backend; $env:JAVA_HOME='E:\OpenSources\jdk21'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\mvnw.cmd test`

### Task 7：统一 Demo Agent 与正式 Agent 对话链路

状态：已完成。

记录：
- 新增正式 `/api/agent/messages`，复用已有持久化 Agent pipeline 写入 `conversation_session`、`agent_message`、`agent_run` 和 `memory_recall_event`。
- Demo 页面 Agent 表单改调正式 API。
- 移除 Demo 专用 `/api/demo/agent/messages` 入口，避免双轨。
- 模型回复失败仍通过异常返回，不保存固定模板助手回复。

验证：
- `AgentControllerTest.messagesUsesPersistentAgentPipeline`
- `cd backend; $env:JAVA_HOME='E:\OpenSources\jdk21'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\mvnw.cmd test`

### Task 8：重排 Demo 页面主叙事，突出“会话生命周期”

状态：已完成。

记录：
- 页面首屏改为 WebSocket 实时流和会话生命周期。
- 强化监听态、会话态、静音计时、增量 transcript、窗口处理和 session 后处理展示。
- 人物 / 记忆作为侧栏，Agent 和数据视图顺着会话沉淀流程下沉。
- 保留局域网 HTTP 麦克风限制提示。

验证：
- `node --check backend/src/main/resources/static/app.js`
- `cd backend; $env:JAVA_HOME='E:\OpenSources\jdk21'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\mvnw.cmd test`

### 总体验证

已运行：
- `cd backend; $env:JAVA_HOME='E:\OpenSources\jdk21'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\mvnw.cmd test`：32 tests passed。
- `cd model-service; .\.venv\Scripts\python -m pytest`：9 tests passed。
- `node --check backend/src/main/resources/static/app.js`：通过。
- PowerShell 解析检查 `scripts/verify-long-stream-windowing.ps1`：通过。
- `.\scripts\verify-mvp.ps1`：通过，包含后端 32 tests passed 与模型服务 9 tests passed。
