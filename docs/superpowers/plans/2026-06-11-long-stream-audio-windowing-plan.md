# 计划：长时流式录音窗口化设计

## 目标

为 Chrono Agent 当前 WebSocket 音频流设计一套可支撑约 1 小时连续录音的 MVP 演进方案，避免把整段音频长期堆积在 Java 进程内存中，并保持 Java 后端作为唯一可信状态源。

本方案重点解决：

- 长时录音内存线性增长。
- 录音结束后才统一处理导致的长尾等待。
- 浏览器异常断开时整段音频丢失风险。
- 现有 `audio_stream_session` 与 `audio_event` 模型如何承载分窗口处理。

## 非目标

- 不做生产级实时逐字转写。
- 不做真正的低延迟边说边显示字幕。
- 不引入消息队列、对象存储或外部流处理系统。
- 不改成独立向量数据库或跨账号声纹匹配。
- 不重做数据库主模型，只做最小增量扩展。

## 约束

- 保持 Java 后端为唯一可信状态源。
- Python 模型服务保持无状态，只接收单个音频窗口的分析请求，不直接写 PostgreSQL。
- 继续使用本地文件系统存储音频。
- 延续当前已确认技术栈：Spring Boot、Spring WebSocket、PostgreSQL、Flyway、FastAPI、`RestTemplate`、`fastjson2`。
- 浏览器 Demo 仍由 Java 静态资源托管，入口仍是 `http://localhost:8080/`。
- 所有查询和写入继续带 `userId` 隔离。

## 输入文档

- 已参考 [AGENTS.md](file:///i:/Chrono%20Agent/AGENTS.md)
- 已参考 [2026-06-11-chrono-agent-demo-flow-design.md](file:///i:/Chrono%20Agent/docs/superpowers/specs/2026-06-11-chrono-agent-demo-flow-design.md)
- 已参考 [2026-06-11-chrono-agent-technical-solution.md](file:///i:/Chrono%20Agent/docs/superpowers/specs/2026-06-11-chrono-agent-technical-solution.md)
- 未找到 `docs/PROJECT_STATUS.md`
- 未找到 `docs/DECISIONS.md`

## 涉及文件或模块

- `backend/src/main/resources/static/app.js`
- `backend/src/main/java/ai/chrono/backend/websocket/AudioStreamWebSocketHandler.java`
- `backend/src/main/java/ai/chrono/backend/websocket/AudioStreamWebSocketConfig.java`
- `backend/src/main/java/ai/chrono/backend/demo/DemoPipelineService.java`
- `backend/src/main/java/ai/chrono/backend/audio/*`
- `backend/src/main/resources/db/migration/*`
- `backend/src/main/java/ai/chrono/backend/modelclient/*`
- `model-service/app/services/analyze_audio.py`
- `model-service/app/providers/fake.py`

## 推荐方案

### 1. 窗口策略

建议采用“传输分块 + 服务端时间窗口”的双层模型：

- 浏览器继续每 `1000ms` 发送一次 WebSocket 二进制块。
- Java 后端把这些小块追加到当前窗口缓冲。
- 当窗口达到 `30 秒` 或用户主动停止时，关闭当前窗口并生成一个 `audio_event`。
- 同一条长时录音对应：
  - 1 条 `audio_stream_session`
  - N 条 `audio_event`

推荐默认窗口为 `30 秒`，理由：

- 比 `60 秒` 更利于失败重试和内存控制。
- 比 `10 秒` 更少数据库和模型调用开销。
- 对 1 小时录音约产生 `120` 个窗口，数量仍在可控范围内。

### 2. Java 端职责拆分

把当前“整段缓冲到 stop 才处理”的逻辑改成三层：

- **连接层**：负责 WebSocket 建连、收块、断连、stop 控制。
- **窗口层**：负责当前窗口的缓冲、滚动关闭、落盘、创建 `audio_event`。
- **分析层**：复用 `DemoPipelineService`，但输入改成单窗口音频，而不是整段长流。

建议新增概念但不强制新增复杂领域对象：

- 内存里维护 `StreamWindowState`
  - `windowIndex`
  - `windowStartedAt`
  - `accumulatedBytes`
  - `chunkCount`
  - `ByteArrayOutputStream`
- 每达到阈值时调用 `flushWindow(...)`
- `flushWindow(...)` 完成：
  1. 固化当前窗口字节
  2. 保存本地音频文件
  3. 写 `audio_event`
  4. 写 `model_job`
  5. 调 Python 分析
  6. 进入现有 speaker / memory / insight 写入链路
  7. 重置下一个窗口缓冲

### 3. 数据模型建议

当前 `audio_event` 已经适合承载窗口，不必新建主表。建议最小增量补充字段到 `audio_stream_session` 或 `audio_event` 元数据：

优先方案：在 `audio_event` 增加窗口元信息字段：

- `window_index integer`
- `window_started_at timestamptz`
- `window_ended_at timestamptz`
- `is_final_window boolean`

如果想更轻量，也可以先把这些信息放入 `audio_event` 关联的 JSON 扩展字段；但从可检索性和后续聚合考虑，推荐显式列。

同时建议在 `audio_stream_session` 增加汇总状态字段：

- `window_count integer default 0`
- `processed_window_count integer default 0`
- `failed_window_count integer default 0`

这样可以直接在 Demo 页面展示“当前长流已切出多少窗口、成功多少个”。

### 4. 停止与异常恢复

长时流录音必须把“正常 stop”和“异常断开”都设计成可恢复路径：

- **正常 stop**：
  - 先 flush 最后一个不足 30 秒的尾窗口。
  - 再关闭 `audio_stream_session`。
- **异常断开**：
  - 如果当前窗口缓冲达到最小可处理阈值，例如 `>= 3 秒`，则尽量落一个尾窗口。
  - 如果不足阈值，则标记为 `abandoned_tail_window`，避免生成过短垃圾片段。
  - 最后把 `audio_stream_session` 标记为 `closed` 或 `failed`。

### 5. 前端交互建议

前端无需承担窗口切片责任，只需要承担“录音进行中状态”和“服务端窗口进度展示”：

建议新增 WebSocket 状态消息：

- `window_flushed`
- `window_processing_started`
- `window_processing_completed`
- `stream_summary`

前端展示：

- 当前录音已持续时长
- 已发送块数
- 已完成窗口数
- 最近一个窗口处理状态

这样不需要实现实时字幕，也能让长时录音看起来在持续推进，而不是停在“录音中”。

### 6. Python 模型服务边界

Python 服务不需要理解“1 小时长流”，只需要继续分析单个窗口。

这意味着：

- Java 仍负责分窗口。
- Java 负责把每个窗口映射成独立 `audio_event`。
- Python 只接收单个窗口音频并返回：
  - `segments`
  - `speaker_embeddings`
  - `summary`
  - `memory_candidates`
  - `safety`

后续如果要做会话级总摘要，可以由 Java 在所有窗口完成后追加一次“整流汇总任务”，但这不应进入第一阶段。

## 风险

- **窗口数量上升**：1 小时约 120 个窗口，会放大 `audio_event`、`model_job`、`speaker_segment` 写入量。
- **人物聚类重复波动**：分窗口处理会让同一说话人在相邻窗口重复参与匹配，需要保持聚类逻辑稳定。
- **短尾窗口质量差**：最后几秒可能信息量太低，容易生成低价值片段。
- **同步处理阻塞**：如果仍在 WebSocket 线程里同步跑模型分析，长窗口会卡住后续消息处理。
- **Demo 页面噪音增加**：窗口级数据过多会让原始数据区变得拥挤。

## 推荐的 MVP 落地顺序

### Task 1：把整段内存缓冲改成 30 秒窗口缓冲

目标：
把当前 WebSocket 长流从“stop 时一次性处理”改为“满 30 秒自动 flush 一个窗口”。

涉及文件：
- `backend/src/main/java/ai/chrono/backend/websocket/AudioStreamWebSocketHandler.java`
- `backend/src/main/java/ai/chrono/backend/websocket/AudioStreamWebSocketConfig.java`

步骤：
1. 为 `StreamState` 增加窗口计数、窗口起始时间和当前窗口累计字节数。
2. 在 `appendChunk(...)` 中判断是否达到窗口阈值。
3. 抽出 `flushWindow(...)`，把当前窗口转成独立处理单元。
4. stop 时补 flush 尾窗口。
5. 异常断开时根据最小阈值决定是否落尾窗口。

验收标准：
- 录音超过 30 秒时，不需要等 stop，也能在后台开始生成窗口数据。
- Java 进程不再把整小时音频作为一个连续 `byte[]` 持有到结束。
- stop 后仅处理最后一个尾窗口，而不是重新处理整段。

验证方式：
- 手工录制 70 秒流式音频。
- 确认至少生成 2 条以上 `audio_event`。
- 确认 WebSocket 未因内存不断增长而异常断开。

建议 commit：
- feat: window websocket audio stream by 30 second segments

### Task 2：为窗口补齐数据库字段和会话统计

目标：
让窗口级音频在数据库里可追踪、可查询、可审计。

涉及文件：
- `backend/src/main/resources/db/migration/*`
- `backend/src/main/java/ai/chrono/backend/demo/DemoPipelineService.java`
- 相关 JDBC 查询代码

步骤：
1. 为 `audio_event` 增加窗口字段。
2. 为 `audio_stream_session` 增加窗口统计字段。
3. 在每次 flush 和完成处理后更新计数。
4. 在查询状态接口中返回窗口统计。

验收标准：
- 每条窗口音频都能看到所属 `stream_session_id` 和窗口顺序。
- `audio_stream_session` 能展示窗口总数、成功数、失败数。

验证方式：
- 执行数据库查询，确认字段已落库。
- 页面刷新后能看到长流相关统计。

建议 commit：
- feat: persist stream window metadata and counters

### Task 3：把窗口处理从 WebSocket 接收路径中解耦

目标：
避免窗口 flush 后同步调用模型分析，阻塞后续 WebSocket 收块。

涉及文件：
- `backend/src/main/java/ai/chrono/backend/websocket/AudioStreamWebSocketHandler.java`
- `backend/src/main/java/ai/chrono/backend/demo/DemoPipelineService.java`
- `backend/src/main/java/ai/chrono/backend/task/*`

步骤：
1. 明确区分“窗口落盘”和“窗口分析”。
2. flush 时只负责创建 `audio_event` / `model_job`。
3. 使用已有任务机制触发分析，而不是在 WebSocket 线程中直接分析。
4. 分析完成后回填 `processed_window_count` / `failed_window_count`。

验收标准：
- WebSocket 在长时录音期间持续稳定收块。
- 单个窗口分析变慢时，不影响后续窗口继续接收。

验证方式：
- 手动模拟较慢模型服务。
- 录音持续 2 分钟，确认块接收不中断。

建议 commit：
- refactor: decouple stream window flush from analysis execution

### Task 4：增强前端长流状态展示

目标：
让 Demo 页面能表达“长时录音正在按窗口推进”，而不是只有简单的已连接状态。

涉及文件：
- `backend/src/main/resources/static/app.js`
- `backend/src/main/resources/static/index.html`
- `backend/src/main/resources/static/styles.css`

步骤：
1. 增加窗口级状态消息展示。
2. 显示累计录音时长、窗口数、最近窗口处理结果。
3. stop 后显示整条流的汇总信息。

验收标准：
- 用户能看到录音过程中窗口持续完成，而不是只在最后看到结果。
- 页面不会因为窗口级事件过多而明显刷屏。

验证方式：
- 录音 90 秒，观察状态区连续更新。
- stop 后显示最终汇总。

建议 commit：
- feat: show long stream window progress in demo ui

### Task 5：增加长流验证脚本与回归测试

目标：
为窗口化流式录音建立最小可重复验证路径。

涉及文件：
- `backend/src/test/java/*`
- `scripts/*`
- 如有必要补充 `README.md`

步骤：
1. 增加窗口 flush 的单元测试或集成测试。
2. 覆盖正常 stop、异常断开、短尾窗口三类场景。
3. 补充本地人工验证命令。

验收标准：
- 至少能自动验证窗口化逻辑不会回退到“整段一次性处理”。
- 文档中能明确复现长流验证方法。

验证方式：
- `cd backend; .\mvnw test`
- 视情况补充 `./scripts/verify-mvp.ps1`

建议 commit：
- test: cover long running websocket stream windowing

## 推荐先做

推荐先执行 **Task 1**。

原因：
- 它直接解决“1 小时录音不能稳定处理”的核心瓶颈。
- 不依赖新服务或新依赖。
- 可以在当前 Demo 架构内完成最小闭环。
