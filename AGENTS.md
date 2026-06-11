# Chrono Agent 项目级 AGENTS.md

## 语言

- 默认使用中文沟通和输出文档，除非用户明确要求英文。
- 代码、类名、接口字段、命令和第三方技术名保持英文原文。

## 项目目标

Chrono Agent 是一个类似 Omi 智能项链的个人 Agent。产品目标是在用户授权范围内实时或准实时记录音频和健康数据，沉淀生活上下文，并为用户提供心理支持和生活助手能力。

当前 MVP 聚焦：

- 音频上传和流式音频会话。
- 健康事件写入。
- 语音转写、说话人分离、声纹聚类和匿名人物分析。
- 用户后续标注未注册说话人身份。
- Agent 会话、消息存储、短期上下文和长期个人记忆。
- 每日复盘、主动关怀、人物洞察和安全提示。

当前 MVP 不做：

- 外部数据接入。
- 真实硬件固件、蓝牙协议和移动端后台录音。
- 插件市场。
- 独立任务系统。
- 自建或本地部署独立向量数据库。
- 跨账号声纹匹配。
- 医学诊断、心理治疗、药物建议或临床决策。

## 已确认技术决策

- 后端使用 Java。
- Java 构建工具使用 Maven。
- Java 版本使用 Java 21。
- Java 后端使用 Spring Boot。
- Java WebSocket 使用 Spring WebSocket。
- Java 调 Python 模型服务使用 `RestTemplate`。
- Java/Python JSON 序列化边界使用 `fastjson2`，模型接口字段名保持 snake_case。
- 模型能力使用 Python。
- Python 版本使用 Python 3.11+。
- Python 模型服务使用 FastAPI。
- 数据库使用 PostgreSQL 16。
- 数据库迁移使用 Flyway。
- 本地开发使用 Docker Compose。
- 默认端口：
  - Java 后端：`8080`
  - Python 模型服务：`8000`
  - PostgreSQL：`5432`
- 音频 MVP 先使用本地文件系统存储。
- 用户隔离 MVP 先使用 `userId`。
- 音频分析和声纹能力第一阶段先用 fake provider，但必须通过 provider adapter 预留真实模型替换能力。
- Agent 回复使用 OpenRouter 上的 NVIDIA Nemotron 3 Nano Omni 模型，默认模型 ID 为 `nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free`。
- Agent 回复如果 LLM 不可用必须返回失败，不能静默降级成固定模板回复。
- Agent 召回向量库使用阿里云 DashVector。
- 文本向量生成默认使用 OpenRouter 上的 `nvidia/llama-nemotron-embed-vl-1b-v2:free`，DashVector collection 维度必须与 `CHRONO_EMBEDDING_DIMENSION` 一致，当前默认 `2048`。
- 声纹匹配默认阈值：
  - 高置信阈值：`0.82`
  - 低置信阈值：`0.70`
- 原始音频保留策略必须配置化，默认值先使用 `30` 天。
- 敏感候选记忆是否自动写入必须支持用户个人配置。
- 危机场景采用保守支持策略，不做诊断。
- 删除能力第一期先打基础，保留软删除、失效和审计能力。
- API 和本地运行说明在 `README.md` 中体现即可。
- 本地 Demo 页面由 Java 后端静态资源提供，默认入口为 `http://localhost:8080/`。

## 架构边界

### Java 后端

Java 后端是产品状态的唯一可信来源，负责：

- API。
- 静态 Demo 页面和 WebSocket 音频流入口。
- 鉴权和用户隔离入口。
- 持久化。
- 音频事件、健康事件和时间线。
- 模型任务状态、幂等、重试和错误记录。
- 说话人聚类、用户标注、标签建议和人物洞察。
- Agent 会话、消息、Agent run、短期上下文和长期个人记忆。
- 数据保留、删除、隐私、安全和审计。

### Python 模型服务

Python 模型服务是模型能力适配层，尽量保持无状态，负责：

- 语音活动检测。
- 语音转文字。
- 说话人分离。
- 声纹向量提取。
- 情绪和压力信号提取。
- 会话摘要。
- 生活提醒候选和记忆候选抽取。
- Agent 回复生成。
- 文本 embedding 生成。
- DashVector 向量索引写入和召回查询。
- 安全风险初筛。

Python 服务不得直接写 PostgreSQL，不得直接修改用户记忆或人物身份。所有持久化和最终决策由 Java 后端完成。

## 核心领域术语

- **短期上下文**：当前 Agent run 内临时使用的上下文，不作为长期事实保存。
- **会话记录**：来自录音、聊天、每日总结或手动记录的结构化证据层，对应 `conversation_memory`。
- **个人记忆**：可跨会话召回的长期记忆，对应 `memory_item`。
- **候选记忆**：模型或规则提出、等待自动保存或用户确认的记忆，对应 `memory_write_candidate`。
- **说话人片段**：音频中的一段说话内容，对应 `speaker_segment`。
- **说话人聚类**：账号内匿名或已标注人物聚类，对应 `speaker_cluster`。
- **标签建议**：系统对匿名人物提出的身份标签候选，对应 `speaker_label_suggestion`，不能自动确认为真实身份。
- **人物洞察**：围绕匿名或已标注人物生成的互动复盘线索，对应 `person_insight`。

## 数据和隐私规则

- 不跨账号共享或匹配声纹。
- 未注册人物只能匿名聚类和分析，不能自动推断真实姓名、年龄、性别、职业、敏感属性或关系类型。
- 文本中出现“我是张三”“我叫张三”只能生成标签建议，不能自动改名。
- 用户确认后才可以把匿名人物显示名更新为用户提供的标签。
- 声纹向量必须加密存储或以加密引用形式保存。
- 日志禁止输出：
  - 完整音频转写。
  - 声纹向量。
  - 完整模型 prompt。
  - 完整心理状态描述。
  - 未脱敏健康数据详情。
- 所有用户数据查询必须带 `userId` 隔离。
- 个人记忆必须带证据引用。
- 新记忆替代旧记忆时，不直接物理删除旧记忆；应设置 `invalid_at` 和 `superseded_by`。

## 代码和实现规则

- 修改前先理解现有结构和文档，不擅自换栈或引入无关依赖。
- 当前已批准的依赖范围限于实施计划中的 Spring Boot、FastAPI、PostgreSQL、Flyway、pytest、Docker Compose 等基础依赖。
- 当前已批准的 Java 增量依赖还包括 Spring WebSocket 和 `fastjson2`，用于浏览器实时音频流和 Java/Python JSON 边界。
- 当前已批准的 Python 增量依赖还包括 `dashvector`，用于连接阿里云 DashVector。
- 如需新增计划外依赖，必须先说明原因、收益和代价，并等待用户确认。
- 优先按 `docs/superpowers/plans/2026-06-11-chrono-agent-mvp.md` 分阶段实现。
- 每个阶段保持最小可验证改动。
- 优先使用项目既有模式，不做无关重构。
- Python 模型 provider 必须通过 adapter 隔离，fake provider 可随时替换为真实 ASR 和声纹 provider。
- Python Agent 回复 provider 必须通过 OpenRouter adapter 隔离，默认使用 NVIDIA Nemotron 3 Nano Omni。
- Python 向量召回 provider 必须通过 OpenRouter embedding adapter 和 DashVector store adapter 隔离。
- `backend/modelclient` 必须保持 provider adapter 边界，当前使用 `RestTemplate` 调用 Python FastAPI，并使用 `fastjson2` 序列化请求和解析响应。
- 前端 Demo 支持文件上传、浏览器麦克风录音、`/ws/audio` WebSocket 流、健康事件、人物标注、候选记忆确认、Agent 对话和已存储用户数据展示。
- 浏览器麦克风能力只能在安全上下文中使用；本地 Demo 推荐通过 `localhost` 或 `127.0.0.1` 访问，局域网 HTTP 地址需要提示用户改用 HTTPS 或本机访问。
- 当前版本不做外部数据接入；不要添加导入第三方数据源的 API。
- Windows/PowerShell 中读取中文 UTF-8 文件必须显式指定 `-Encoding UTF8`。

## Git 安全

- 当前项目计划初始化 git 仓库。
- 初始化前先确认当前目录。
- 不执行 `git add .`。
- 不执行 `git reset --hard`、`git clean`、`git checkout --` 等破坏性命令，除非用户明确要求。
- 未经用户确认不执行 commit、push、stash pop。
- 如果用户批准提交，只添加当前任务相关文件。

## 验证规则

修改后优先运行相关验证：

- Java 后端：

  ```powershell
  cd backend
  .\mvnw test
  ```

- Python 模型服务：

  ```powershell
  cd model-service
  .\.venv\Scripts\python -m pytest
  ```

- MVP 端到端验证：

  ```powershell
  .\scripts\verify-mvp.ps1
  ```

如果验证无法运行，必须说明原因，并给出人工验证步骤。

## 关键文档

- 设计规格：`docs/superpowers/specs/2026-06-11-chrono-agent-design.md`
- 技术方案：`docs/superpowers/specs/2026-06-11-chrono-agent-technical-solution.md`
- Demo 设计：`docs/superpowers/specs/2026-06-11-chrono-agent-demo-flow-design.md`
- 实施计划：`docs/superpowers/plans/2026-06-11-chrono-agent-mvp.md`

## 完成任务后的默认输出

完成任务后默认输出：

- 做了什么。
- 改了哪些文件。
- 如何验证。
- 是否有未完成事项。
- 建议 commit message。
