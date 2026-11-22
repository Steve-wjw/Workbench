-- Source SHA-256: 03e4edc40801c6a64f7118901101c9da80f7eec0b71b465bdbf2865c106aaf17
-- Import interview3.md; no AI analysis or technical content rewriting.
-- Execution guide: docs/V1/SQL/数据库脚本使用说明.md.
-- 10 main questions / 28 follow-ups / 12 distinct tags / 32 tag relations.
-- Difficulty from heading: EASY=3 / MEDIUM=5 / HARD=2.
-- Target: exactly one existing ACTIVE Agent module (case-insensitive, trimmed).
-- Defaults: NOT_STARTED / MANUAL; detailed_answer is NULL.
-- Trailing heading parentheses supply difficulty and tags, not title text.
-- Preserve Markdown and follow-up notes; OpenManus has no follow-ups (NULL).
-- Reuse tags by case-insensitive trimmed name; create missing tags.
-- Ambiguous existing tag names cause a rollback, never an arbitrary match.
-- Same trimmed title in the same module: skip content AND tags.
-- Execute the WHOLE file in one connection after backing up the database.
-- Requires answer, follow_up_questions, source_type, and BIGINT identity IDs.
-- No schema changes, document imports, indexing, AI calls, or external requests.

BEGIN;

SET LOCAL lock_timeout = '10s';

-- Serialize duplicate checks and missing-tag creation with other writes.
LOCK TABLE core.interview_questions, core.tags IN SHARE ROW EXCLUSIVE MODE;

DO $agent_interview3_import$
DECLARE
    target_module_id UUID;
    item JSONB;
    tag_name TEXT;
    target_tag_id UUID;
    new_question_id BIGINT;
    imported_count INTEGER := 0;
    skipped_count INTEGER := 0;
    created_tag_count INTEGER := 0;
    tag_relation_count INTEGER := 0;
BEGIN
    BEGIN
        SELECT id INTO STRICT target_module_id
        FROM core.modules
        WHERE lower(btrim(name)) = 'agent' AND status = 'ACTIVE'
        FOR SHARE;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            RAISE EXCEPTION 'No active Agent module found; import cancelled.';
        WHEN TOO_MANY_ROWS THEN
            RAISE EXCEPTION 'Multiple active Agent modules found; import cancelled.';
    END;

    FOR item IN
        SELECT value FROM jsonb_array_elements(
$interview3_payload$
[
  {
    "question": "AutoGPT 如何实现自主决策？",
    "answer": "AutoGPT 是 2023 年出现的早期自主 Agent 代表项目。\n\nAutoGPT 实现自主决策的核心是一套反馈循环机制，让 LLM 能够自己拆任务、执行、评估、调整，不需要人一步步指挥。\n\n整个循环分这么几步：\n\n1) 目标设定。用户给一个高层目标，比如“帮我调研竞品的定价策略”，AutoGPT 会把它拆成多个可执行的子任务。\n\n2) 任务规划。针对每个子任务制定执行计划，决定要调用什么工具、按什么顺序执行。\n\n3) 任务执行。按计划调用搜索引擎、执行代码、读写文件，把子任务一个个干完。\n\n4) 结果评估。每个子任务做完后，用 LLM 评估结果是否符合预期，有没有达到目标。\n\n5) 策略调整。如果评估不过，就调整计划重来；如果过了，就推进到下一个子任务。\n\n这个循环会一直跑，直到最终目标达成或者达到预设的迭代上限。\n\n反馈循环的流程：用户输入高层目标后，AutoGPT 首先进入任务规划阶段，将目标拆解为子任务列表；然后进入执行阶段，调用各种工具完成当前子任务；执行完成后进入评估阶段，LLM 判断执行结果是否达到预期；如果评估通过且还有剩余子任务，回到执行阶段处理下一个子任务；如果评估不通过，进入调整阶段修改计划，然后重新执行；如果所有子任务完成，输出最终结果。整个过程形成一个闭环。",
    "followUpQuestions": "{\"version\":1,\"items\":[{\"question\":\"AutoGPT 这种自主循环的架构，跟 LangChain 的 Agent 有什么区别？\",\"answer\":\"核心区别在于自主程度。LangChain Agent 通常在单个任务范围内做多步工具调用，链路相对较短。AutoGPT 是多轮自主循环，给一个高层目标后它会自己拆任务、执行、评估、调整，中间不需要人干预，可能循环几十轮才出最终结果。LangChain Agent 更像是一个听话的执行者，AutoGPT 更像是一个项目经理。\"},{\"question\":\"AutoGPT 的记忆系统用向量数据库，检索不准怎么办？\",\"answer\":\"检索不准确实是个问题。一方面可以在存的时候做信息压缩，把操作历史总结成关键结论再存，减少噪声。另一方面检索的时候用多路召回，语义检索和关键词检索结合，再加个 rerank 层过滤掉不相关的。还有就是记忆不能无限膨胀，要定期清理过期的、重复的信息，保持记忆库的质量。\"},{\"question\":\"如果让你优化 AutoGPT 的执行效率，从哪些方面入手？\",\"answer\":\"几个方向。第一是任务并行化，现在是串行执行子任务，如果子任务之间没有依赖关系，可以并行跑。第二是缓存常见操作的结果，比如搜索同一个关键词不用每次都调 API。第三是用小模型做简单决策，只有复杂任务才用旗舰大模型，节省 token。第四是加执行上限和超时机制，防止死循环消耗大量资源。\"}]}",
    "difficulty": "HARD",
    "tags": [
      "大模型",
      "AI Agent",
      "LangChain",
      "AutoGPT"
    ]
  },
  {
    "question": "什么是 A2A 协议，它的核心架构及主要组件有哪些？",
    "answer": "**A2A 协议**是 Google 主导开发的开放协议，专门用来让不同的 AI 智能体互相通信、协作，不管是不同平台还是不同框架构建的 Agent 都能对话。核心架构包含 9 个主要组件：\n\n1) **Agent Card**：一个公开的 JSON 文件，里面写清楚这个 Agent 能干啥、暴露哪些技能、端点 URL 是什么、怎么做身份验证，客户端靠它来发现和识别 Agent。\n\n2) **A2A Server**：实现 A2A 协议方法的 HTTP 端点，负责接收请求、管理任务执行。\n\n3) **A2A Client**：发送请求到 A2A Server 的应用程序或其他 Agent，比如发一个 tasks/send 请求让远端 Agent 干活。\n\n4) **Task**：客户端发起的工作单元，每个 Task 有唯一 ID，状态会从 submitted 变成 working，最后变成 completed 或 failed。\n\n5) **Message**：客户端与 Agent 之间的通信回合，一个 Message 里面包含多个 Part。\n\n6) **Part**：消息或工件中的最小内容单元，可以是纯文本、文件或结构化 JSON。\n\n7) **Artifact**：Agent 在任务中生成的输出，比如生成的文件、最终的结构化数据。\n\n8) **Streaming**：长时间运行的任务支持 tasks/sendSubscribe，客户端通过 SSE 实时接收任务状态更新。\n\n9) **Push Notifications**：服务器可以主动把任务更新推送到客户端提供的 webhook URL。\n\n整个协议基于 JSON-RPC 2.0 通过 HTTP 进行消息交换，流式传输走 SSE 协议。",
    "followUpQuestions": "{\"version\":1,\"items\":[{\"question\":\"A2A 协议里的 Task 状态机是怎么流转的？\",\"answer\":\"详细解答可以看：A2A 协议的工作流程是怎样的？\\n\\nTask 状态从 submitted 开始，表示任务已提交但还没开始处理。Server 开始处理后变成 working，中间可能会有多次状态更新。最终只有两个终态：completed 表示成功完成，failed 表示失败。如果是支持流式的任务，working 期间会通过 SSE 持续推送中间结果。\"},{\"question\":\"Agent Card 里的技能描述是怎么让客户端理解的？\",\"answer\":\"Agent Card 里的技能用结构化 JSON 描述，包含技能名称、输入参数的 JSON Schema、输出格式等。客户端拿到 Agent Card 后可以解析这些 Schema，知道调用这个技能需要传什么参数、能拿到什么结果。有点像 OpenAPI 规范描述 REST 接口。\"},{\"question\":\"如果两个 Agent 之间网络断了，A2A 协议有什么容错机制？\",\"answer\":\"A2A 协议本身不强制规定容错策略，但提供了几个机制可以用：Task 有唯一 ID，断线重连后可以通过 tasks/get 查询任务状态，不用重新发起。Push Notification 支持 webhook 回调，即使客户端断线，任务完成后服务端也能主动通知。具体的重试策略、超时时间这些由各自实现决定。\"}]}",
    "difficulty": "MEDIUM",
    "tags": [
      "大模型",
      "AI",
      "A2A",
      "系统架构"
    ]
  },
  {
    "question": "A2A 协议有哪五大设计原则？",
    "answer": "A2A 协议就是 AI 智能体界的通用语言，目的是打破各个 Agent 框架之间的隔离，让它们能无障碍交流。设计上遵循了五大核心原则：\n\n1) 拥抱 Agentic 能力：把 Agent 当人看，不是当工具用。传统 API 是我调你一下你动一下，A2A 允许 Agent 之间像同事一样协作，它们可以自己商量怎么完成任务，不需要共享内存，也不需要知道对方底层代码，只通过协议沟通就行。\n\n2) 建立在现有标准之上：不造新轮子，底层用的就是 HTTP、SSE、JSON-RPC 这些成熟技术。企业现有的 IT 系统不用推倒重来，直接就能接入，开发成本极低。\n\n3) 默认安全：企业最怕 AI 乱跑。A2A 直接集成了企业级的身份验证和授权机制，跟 OpenAPI 的安全标准看齐，确保 Agent 之间的交互是可控、可追溯的。\n\n4) 支持长时间运行的任务：很多 AI 任务比如写代码、做研究可能要跑好几个小时。A2A 设计了异步机制，支持通过 SSE 实时反馈进度，用户不用傻等，可以随时查看状态。\n\n5) 模态无关：不光能发文字，还能发语音、视频。A2A 原生支持音频、视频流传输，为未来的语音助手、视频分析 Agent 预留了接口。\n\n简单来说，A2A 就是为了建立一个去中心化的 Agent 互联网，让不同厂商的 AI 能在一个安全、标准的框架下，进行长时间、多模态的自主协作。",
    "followUpQuestions": "{\"version\":1,\"items\":[{\"question\":\"A2A 协议怎么保证不同厂商实现的 Agent 真的能互通？\",\"answer\":\"靠 Agent Card 这个机制。每个 Agent 必须发布一个标准格式的 JSON 文件，里面声明自己能干啥、接受什么输入、返回什么输出。客户端拿到 Agent Card 后就知道怎么调用这个 Agent，不管它底层是 LangChain 还是 AutoGPT 还是自研框架。协议层面强制规定了 JSON-RPC 2.0 的调用格式，只要遵守协议，互通就没问题。\"},{\"question\":\"默认安全这个原则具体怎么落地的？\",\"answer\":\"A2A 协议在 Agent Card 里就要求声明支持哪些认证方式，比如 OAuth 2.0、API Key、mTLS 等。每次请求都必须带上认证信息，Server 端会校验。授权层面，Agent Card 也会声明哪些操作需要什么权限，客户端必须先拿到对应权限才能调用。整套机制跟 OpenAPI 的安全规范对齐，企业现有的 IAM 系统可以直接接管。\"},{\"question\":\"长时任务跑到一半客户端断线了怎么办？\",\"answer\":\"Task 有唯一 ID，断线重连后通过 tasks/get 接口查询任务状态就行，不用重新发起。如果配置了 Push Notification，任务完成后服务端会主动往 webhook URL 推送结果。另外 A2A 协议支持任务状态持久化，Server 重启后任务信息也不会丢。\"}]}",
    "difficulty": "MEDIUM",
    "tags": [
      "大模型",
      "AI",
      "A2A",
      "系统架构"
    ]
  },
  {
    "question": "A2A 协议的工作原理是怎样的？",
    "answer": "A2A 协议的核心作用是让客户端 Agent 和远程 Agent 之间顺利沟通。客户端 Agent 负责创建并下发任务，远程 Agent 根据任务提供信息或执行操作。\n\n整个过程中，A2A 提供了四个关键能力：\n\n1) 能力发现：每个 Agent 都有一张 Agent Card，以 JSON 格式描述它能做什么。客户端 Agent 可以通过这些卡片，找到最适合执行当前任务的远程 Agent，并通过 A2A 协议与之建立通信。\n\n2) 任务管理：客户端向远程 Agent 发送 Message，远程 Agent 根据请求内容自主决定是否创建 Task 来跟踪执行。每个 Task 都有协议定义的生命周期，有些任务可以立刻完成，对于耗时较长的任务，双方可以持续沟通、保持状态同步。任务完成后会产生一个 Artifact，也就是最终的执行结果。\n\n3) 协作能力：Agent 之间可以直接通信，发送包含上下文、回复、Artifact 或用户指令的消息，便于协同完成更复杂的任务。\n\n4) 用户体验协商：每条消息可以包含多个 Part，每个 Part 代表一个完整的内容片段，比如一张图片或一段文本。每个 Part 都有明确的 MIME 类型，客户端和远程 Agent 可以据此协商最合适的展示格式，支持 iframe、视频、网页表单等不同的 UI 形态。",
    "followUpQuestions": "{\"version\":1,\"items\":[{\"question\":\"Agent Card 一般放在哪里，客户端怎么发现它？\",\"answer\":\"Agent Card 通常放在 Agent 服务的固定路径下，比如 /.well-known/agent-card.json，跟 OAuth 的 .well-known/openid-configuration 类似。客户端可以通过服务发现机制获取已知 Agent 的域名列表，然后逐个请求这个固定路径拿到 Agent Card。也可以搞一个中心化的 Agent 注册表，所有 Agent 启动后把自己的 Card 注册上去，客户端直接查注册表。\"},{\"question\":\"Task 执行到一半远程 Agent 挂了怎么办？\",\"answer\":\"A2A 协议本身不强制规定容错策略，但提供了基础设施来支撑。Task 有唯一 ID，重启后可以恢复状态。如果远程 Agent 支持任务持久化，重启后能从中断点继续；如果不支持，客户端通过 GetTask（早期版本叫 tasks/get）查询会发现任务变成 failed 状态，然后可以决定是重新发起还是切换到其他 Agent。高可用场景下，建议远程 Agent 后面挂个任务队列，比如 Kafka 或 RabbitMQ，任务不会因为单点故障丢失。\"},{\"question\":\"多个 Agent 协作时，消息上下文怎么传递的？\",\"answer\":\"每条 Message 里有一个 parts 数组，可以塞多个 Part。除了用户可见的内容，还可以用 data 类型的 Part 或 metadata 字段带上中间结果、任务上下文等；多轮交互则用同一个 contextId 串起来，远程 Agent 据此关联之前的对话。远程 Agent 收到消息后解析这些 Part，就能拿到完整的上下文。如果上下文太大，也可以只传引用，比如一个 URL 指向之前生成的 Artifact，远程 Agent 自己去拉取。\"}]}",
    "difficulty": "EASY",
    "tags": [
      "大模型",
      "AI",
      "A2A",
      "技术栈"
    ]
  },
  {
    "question": "A2A 协议的工作流程是怎样的？",
    "answer": "A2A 协议的工作流程可以分成 5 个阶段：发现、启动、处理、交互、完成。\n\n1) 发现：客户端向 `/.well-known/agent-card.json` 发起 HTTP GET 请求，拿到远端智能体的 Agent Card。这个 Card 里包含了智能体的名称和描述、能力清单、服务端点 URL、认证方式等元数据，客户端可以根据这些信息快速筛选出合适的智能体来干活。\n\n2) 启动：客户端通过 JSON-RPC 调用 `SendMessage` 或 `SendStreamingMessage`（早期版本叫 `tasks/send` / `tasks/sendSubscribe`）向目标智能体发送消息，由服务端决定是否创建 Task 并生成 Task ID。前者是普通请求，返回 Task 对象或直接回复的 Message；后者是流式请求，服务端通过 Server-Sent Events 推送状态更新。\n\n3) 处理：远端智能体接收请求后，把任务状态从 `submitted` 切换到 `working`，在内部执行模型推理或外部工具调用。对于订阅式任务，会持续推送 `TaskStatusUpdateEvent` 和 `TaskArtifactUpdateEvent`，让客户端实时获取进度或中间成果。\n\n4) 交互：当智能体在处理过程中需要额外输入时，会发出 `input-required` 状态更新，并携带一条 Message 请求。客户端收到后可以在新消息里带上相同的 taskId 和 contextId，通过 `SendMessage` 或 `SendStreamingMessage` 补充用户输入，保持会话的连续性和上下文一致。\n\n5) 完成：任务进入终态，比如 `completed`、`failed` 或 `canceled`。客户端可以选择主动拉取最终的 Task 对象，也可以继续通过 SSE 或 Webhook 机制订阅 `TaskStatusUpdateEvent`，并获取以 JSON Artifact 形式封装的最终结果。",
    "followUpQuestions": "{\"version\":1,\"items\":[{\"question\":\"A2A 协议为什么选择 JSON-RPC 而不是 gRPC？\",\"answer\":\"主要是为了兼容性和部署便利性。JSON-RPC 基于 HTTP/HTTPS，能穿透绝大多数企业防火墙，不需要额外配置。gRPC 虽然性能更好，但依赖 HTTP/2 和 Protocol Buffers，很多老旧网关和代理不支持，部署成本高。A2A 的设计目标是让不同厂商、不同技术栈的智能体能快速对接，选 JSON-RPC 是最务实的方案。不过后来规范也加入了 gRPC 和 HTTP+JSON（REST）绑定，Agent 可以在 Agent Card 里声明自己支持哪几种。\"},{\"question\":\"tasks/send 和 tasks/sendSubscribe 两种调用方式，实际项目中怎么选？\",\"answer\":\"（现行规范中这两个方法分别叫 SendMessage 和 SendStreamingMessage，选择思路不变。）看任务的执行时长和交互需求。短任务、几秒内能返回结果的，用 `tasks/send` 就够了，简单省事。长任务、需要实时看进度的，比如大模型生成文本或者多轮推理，用 `tasks/sendSubscribe`，客户端通过 SSE 拿到流式更新，用户体验好很多。另外如果任务中间可能需要补充输入，也得用订阅式，不然客户端不知道什么时候该响应。\"},{\"question\":\"A2A 和 MCP 协议有什么区别？\",\"answer\":\"定位不一样。MCP 是让大模型调用外部工具和数据源的协议，解决的是模型和工具之间的连接问题。A2A 是智能体之间的协作协议，解决的是多个智能体如何发现、通信、协调任务的问题。打个比方，MCP 是给模型装上手和眼睛，A2A 是让多个有手有眼的智能体一起合作干活。两者可以配合使用，一个智能体内部用 MCP 调工具，多个智能体之间用 A2A 协作。\"}]}",
    "difficulty": "EASY",
    "tags": [
      "大模型",
      "AI",
      "A2A"
    ]
  },
  {
    "question": "A2A 协议与 MCP 协议的关系是怎样的？",
    "answer": "A2A 和 MCP 是两个互补的协议，共同推动智能体生态的发展。\n\n在智能体与外部系统之间实现互操作，标准化协议至关重要，主要聚焦两个密切关联的领域：工具与智能体。工具是具有结构化输入输出、预定义行为的基本单元；智能体则是能够调用工具、进行逻辑推理并与用户互动的自主应用。\n\n可以把 A2A 想象成智能体之间的“电话簿”，负责发现、呼叫和协作；MCP 则像工具的“使用说明书”，为智能体接入外部数据源和服务提供统一接口。两者结合，才能构建既能高效联动工具，又能自由组织多方智能体的完整生态。\n\n具体来看：\n\n1) MCP 由 Anthropic 于 2024 年发布，为 AI 模型提供和外部数据源、工具连接的标准化方式。它允许 AI 模型通过统一接口访问文件、数据库、API 等资源，实现工具接入层的标准化。MCP 通常基于 JSON-RPC 2.0，传输方式包括本地 stdio 和基于 HTTP 的远程流式传输，具体形态以当前官方规范为准。\n\n2) A2A 是 Google 推动的应用层协议，用来让 AI 智能体之间交换任务、状态、消息和产物。它可以承载自然语言内容，但协议本身并不是简单的“自然语言聊天”，而是围绕 Agent Card、Task、Message、Artifact 等结构化对象来组织协作。",
    "followUpQuestions": "{\"version\":1,\"items\":[{\"question\":\"如果我想让多个智能体协作完成一个任务，应该怎么设计架构？\",\"answer\":\"一般有两种模式。第一种是 Orchestrator 模式，搞一个中央编排智能体，它通过 A2A 协议调度其他专业智能体，协调任务分配和结果汇总。第二种是 Peer-to-Peer 模式，智能体之间平等通信，每个智能体根据自己的能力决定是否接手任务。实际项目中 Orchestrator 模式更常见，因为控制流清晰、调试方便。每个被调度的智能体内部再通过 MCP 调用所需的工具和数据。\"},{\"question\":\"MCP 和传统的 API 调用有什么本质区别？\",\"answer\":\"传统 API 调用是开发者写死的，代码里明确指定调哪个接口、传什么参数。MCP 是让 AI 模型自己决定调什么、怎么调。模型看到 MCP Server 暴露的 Tools 列表和描述，根据用户意图自己选择合适的工具并构造参数。相当于把调用决策权从开发者转移到了模型，灵活性大大提升。当然代价是模型可能选错或参数填错，所以需要 Schema 约束和权限控制。\"},{\"question\":\"A2A 协议里智能体之间怎么保证消息不被篡改？\",\"answer\":\"A2A 支持多层安全机制。传输层用 HTTPS/TLS 加密，防止中间人攻击。认证层支持 OAuth 2.0、mTLS、签名 JWT 等，确保身份可信。协议本身没有定义消息级签名，规范里的签名是 Agent Card 签名，用来校验 Card 的来源和完整性；如果要对消息本身防篡改，可以在应用层自行加签名，发送方用私钥签名，接收方用公钥验签，任何篡改都会导致签名校验失败。企业级部署一般会结合使用，mTLS 做双向认证加上 JWT 签名双重保障。\"}]}",
    "difficulty": "MEDIUM",
    "tags": [
      "大模型",
      "AI",
      "A2A",
      "MCP"
    ]
  },
  {
    "question": "什么是 Google ADK？",
    "answer": "ADK (Agent Development Kit) 是 Google 推出的智能体开发工具包，核心思想是让智能体开发回归软件工程本质。开发者可以用熟悉的编码范式，像搭积木一样组合各种模块，快速构建能自主决策、多模块协作的 AI 智能体。\n\n举个例子，要做一个法律咨询系统，可能需要法律条款分析 Agent、案例匹配 Agent、文档生成 Agent 三个 Agent 协同完成。ADK 就是帮你把这些 Agent 串起来、管起来的框架。\n\nADK 的两大核心能力：\n\n1) 模块化：每个 Agent 是独立的执行单元，可以单独开发、单独测试、单独升级，改一个模块不会把整个系统搞崩\n\n2) 协调能力：负责多 Agent 之间的分工调度，支持三种协调方式\n\n三种协调方式的特点：\n\nLLM Agents 方式：让大模型来当“调度员”，根据用户输入动态决定调用哪个 Agent。适合需要灵活理解自然语言的场景，比如用户问“帮我分析这个合同有没有问题”，LLM 会自动判断要先调法律条款分析 Agent 还是案例匹配 Agent。\n\n工作流方式：预先定好执行顺序，按照顺序、并行或循环的模式跑，不依赖 LLM 做决策。适合流程固定的场景，比如“先提取数据→再清洗→最后生成报告”这种确定性流程。\n\n自定义 Agent 方式：直接继承 BaseAgent 基类，自己实现控制逻辑。适合有特殊业务需求、需要对接非标系统的场景。",
    "followUpQuestions": "{\"version\":1,\"items\":[{\"question\":\"ADK 的 LLM 协调模式和工作流模式怎么选？有没有混用的场景？\",\"answer\":\"看流程是否确定。如果业务流程固定，比如“数据采集→清洗→入库”，用工作流模式，省 token 还稳定。如果用户输入多变、需要动态判断下一步干啥，用 LLM 协调模式。混用场景很常见，比如外层用 LLM 协调来理解用户意图、分发任务，内层每个子任务用工作流模式跑固定流程，兼顾灵活性和稳定性。\"},{\"question\":\"多 Agent 协作时，Agent 之间怎么传递上下文信息？\",\"answer\":\"ADK 提供了 Session 机制来管理上下文。每个 Session 有独立的状态空间，Agent 可以往里面写数据，下游 Agent 可以读取。关键是要约定好数据格式和字段命名，不然 Agent A 写的东西 Agent B 解析不了。复杂场景下建议定义一套统一的上下文 Schema，所有 Agent 都按这个规范来。\"},{\"question\":\"ADK 相比直接用 LangChain 做多 Agent，有什么实际优势？\",\"answer\":\"三点。第一，ADK 的多 Agent 协调是原生设计，不是后加的，用起来更自然。第二，和 Google Cloud 生态集成好，部署到 Vertex AI 上基本开箱即用。第三，Agent 的生命周期管理更完善，状态持久化、故障恢复这些 LangChain 要自己搞，ADK 框架层面就支持了。当然如果你不用 GCP，这些优势就没那么明显了。\"}]}",
    "difficulty": "EASY",
    "tags": [
      "AI",
      "大模型"
    ]
  },
  {
    "question": "什么是工具调用 Tool Calling？如何利用 Spring AI 实现工具调用？",
    "answer": "工具调用 (Tool Calling)，也常被称为 Function Calling，是一种允许 AI 大模型在对话过程中，根据需要请求执行外部工具来完成特定任务的机制。\n\nAI 模型本身可能不具备实时查询天气、操作数据库或访问特定 API 的能力，通过工具调用，它可以识别出什么时候需要这些外部能力，并生成一个包含工具名称和所需参数的请求。应用程序接收到这个请求后，实际执行相应的工具，并将执行结果返回给 AI 模型，模型再基于这个结果继续对话或生成最终答案。\n\n注意，真正执行工具的是我们的应用程序，而非 AI 服务器本身。\n\nSpring AI 极大地简化了工具调用的实现：\n\n1) 定义工具：通常使用注解方式，在一个 Java 类中，将希望作为工具的方法标记上 `@Tool` 注解。方法的参数可以使用 `@ToolParam` 注解来提供描述和指定是否必需。Spring AI 支持多种 Java 类型作为参数和返回值，但返回值需要可序列化。\n\n2) 注册与使用工具：\n*   按需使用：在构建 `ChatClient` 请求时，通过 `.tools()` 方法直接传入工具类的实例。\n*   全局使用：在构建 `ChatClient` 时，通过 `.defaultTools()` 注册默认工具，这些工具对该 `ChatClient` 发起的所对话都可用。\n*   集中注册：可以创建一个配置类，使用 `@Bean` 方法将所有工具实例化并通过 `ToolCallbacks.from()` 统一注册为一个 `ToolCallback[]` 数组，方便管理和注入。\n\n3) 调用流程：当使用配置了工具的 `ChatClient` 进行对话时，如果 AI 模型判断需要使用某个工具，Spring AI 框架会自动执行以下步骤，无需开发者关心：\n*   解析 AI 模型返回的工具调用请求（包含工具名和参数）。\n*   根据工具名找到对应的 Java 方法并执行。\n*   将工具执行的结果转换并返回给 AI 模型。\n*   AI 模型根据工具结果生成最终回复。",
    "followUpQuestions": "{\"version\":1,\"items\":[{\"question\":\"如果模型选错了工具，或者生成的参数不对怎么办？\",\"answer\":\"几个处理思路。1）工具描述写详细点，让模型更容易理解每个工具的适用场景，减少选错概率。2）参数校验要严格，不合法直接返回错误信息，模型看到错误后通常会修正参数重试。3）可以在工具返回结果里加引导，比如“查询失败，请确认城市名称是否正确”，模型会据此调整。4）实在不行设置 fallback 逻辑，捕获异常后返回友好提示而不是让整个对话崩掉。\"},{\"question\":\"工具调用和 RAG 有什么区别？什么时候用工具调用，什么时候用 RAG？\",\"answer\":\"RAG 是“查知识”，从静态知识库里检索相关文档，适合回答“什么是 XXX”这类知识性问题。工具调用是“做动作”，执行实时查询或操作，适合“帮我查一下今天股价”、“帮我发一封邮件”这类需要外部交互的任务。很多场景两者会组合使用：先用 RAG 找到相关背景知识，再用工具调用获取实时数据，最后综合生成回答。\"},{\"question\":\"Spring AI 底层是怎么把工具定义传给模型的？\",\"answer\":\"Spring AI 会把带 `@Tool` 注解的方法解析成 JSON Schema 格式的工具描述，包含工具名称、功能描述、参数列表、参数类型、是否必填等信息。调用模型 API 时，这些工具描述会作为 `tools` 或 `functions` 参数一起发送。模型推理时会参考这些描述来决定是否调用工具以及怎么构造参数。不同模型厂商的 API 格式略有不同，Spring AI 做了适配层统一处理。\"}]}",
    "difficulty": "MEDIUM",
    "tags": [
      "后端",
      "编程导航"
    ]
  },
  {
    "question": "什么是 OpenManus？它的实现原理是什么？",
    "answer": "OpenManus 是一个开源的自主规划智能体项目。它的核心亮点在于其“自主执行”能力，能够自主规划任务，并且在本地工作目录（或可选的 Docker 沙箱）中调用各种工具（如编写代码、爬取数据）来完成用户指定的复杂任务。它的实现原理主要基于以下几点：\n\n1. **分层代理架构**：OpenManus 采用了分层的代理架构，不同层次的代理负责不同的功能，方便系统的扩展和维护。主要代理层次包括 BaseAgent（基础代理，管理状态和执行循环）、ReActAgent（实现 ReAct 模式）、ToolCallAgent（扩展了工具调用能力）以及具体的智能体实例（如 Manus）。\n\n2. **ReAct 模式**：OpenManus 的工作流程遵循“思考-行动-观察”的循环，ToolCallAgent 的 `think` 方法负责与大模型交互、选择工具，`act` 方法负责执行工具，并将结果反馈给大模型进行下一步决策。\n\n3. **工具系统**：OpenManus 拥有一个灵活的工具系统。所有工具都继承自 BaseTool 抽象基类，提供统一的接口和规范化的参数描述，方便大模型理解和调用。ToolCollection 类可以管理多个工具实例，实现了工具系统的可插拔性。\n\n4. **核心组件**：包括记忆系统（Memory 类存储对话历史和中间状态）、大模型（LLM 类提供思考和决策能力）以及流程控制（AgentState 和执行循环管理状态转换和任务流程）。\n\n5. **特殊工具设计**：比如，Terminate 工具是让智能体自主决定何时结束任务，AskHuman 工具则是让智能体在遇到困难时向人类寻求帮助。\n\n6. **MCP 协议**：最新版本的 OpenManus 已经支持，通过 MCPClients 类将 MCP 服务集成到其工具系统中，让远程工具能够像本地工具一样被调用。",
    "followUpQuestions": null,
    "difficulty": "HARD",
    "tags": [
      "后端",
      "编程导航"
    ]
  },
  {
    "question": "Agent 死循环问题有遇到过吗？如何解决？",
    "answer": "遇到过，这是 Agent 开发里非常典型的问题。\n\n我们当时做的是一个物流查询 Agent，正常流程是“用订单号查物流单号 -> 用物流单号查物流详情 -> 返回结果”。但上线后发现有一定概率会出现死循环：订单号给到大模型，拿到了物流单号，再把订单号和物流单号一起给到大模型，模型又返回一个物流单号，没有调用查物流详情的工具，也没有返回终止标识，Java 侧就一直循环调用，直到超时。\n\n分析下来主要是三个原因：提示词没有明确状态流转规则、缺少步骤追踪机制、没有兜底保护策略。\n\n我们做了三个改造：\n\n1) 提示词加状态约束，明确告诉模型“如果已经获取到物流单号，直接调用查物流详情接口，不要重复生成物流单号”。\n\n2) 加状态变量追踪，用 step=1/2/3 这种标识跟踪当前执行到哪一步，每次调用模型时把当前状态带上，模型按状态执行对应动作。\n\n3) 设置兜底机制，最多调用 5 次，超过就强制终止。同时检查每次返回的内容是否有变化，连续两次返回一样的内容直接判定为死循环，触发降级逻辑。",
    "followUpQuestions": "{\"version\":1,\"items\":[{\"question\":\"你刚才说用状态变量追踪步骤，这个状态是存在哪的？模型每次调用不是无状态的吗？\",\"answer\":\"状态存在调用侧，不是模型里。每次调用模型时，我们把当前状态作为输入的一部分传进去，比如 \\\"current_step: 2, completed_steps: [查询订单, 获取物流单号]\\\"。模型看到这个就知道自己该干什么了。本质上是用户外部存储弥补模型无状态的缺陷，LangChain 里的 Memory 组件也是这个思路。\"},{\"question\":\"状态机的方案听起来不错，但实际业务流程可能很复杂，状态机不好维护怎么办？\",\"answer\":\"确实，复杂流程用状态机会很臃肿。这种情况可以用 DAG 来管理，每个节点是一个任务，边表示依赖关系。执行时按拓扑序跑，已经执行过的节点打标记，不会重复执行。LangGraph 也是用图显式编排节点和边的思路，不过它的图允许循环，靠 recursion_limit 限制最大执行步数，比纯状态机灵活，也比纯 ReAct 可控。\"},{\"question\":\"死循环被强制终止后，用户体验怎么保证？总不能直接返回“任务超时”吧？\",\"answer\":\"肯定不能这么粗暴。我们的做法是尽量返回中间结果，比如虽然没查到物流详情，但物流单号已经拿到了，就告诉用户“已查询到物流单号 SF123456，详情获取失败请稍后重试”。同时记录日志方便复盘，如果是系统性问题会触发降级策略，比如暂时切到规则引擎走固定流程。\"},{\"question\":\"有没有遇到过不是死循环但执行路径特别长的情况？比如模型绕了一大圈才完成任务？\",\"answer\":\"有，这叫路径爆炸问题。模型可能会调用一堆不必要的工具来“确认”信息。解决思路一是优化提示词，给模型更明确的执行路径建议；二是加工具调用成本的概念，prompt 里写“尽量用最少的步骤完成任务”；三是做事后分析，统计每个任务的平均路径长度，长得离谱的 case 单独拎出来优化。\"}]}",
    "difficulty": "MEDIUM",
    "tags": [
      "后端",
      "编程导航",
      "场景题"
    ]
  }
]
$interview3_payload$::JSONB) WITH ORDINALITY AS entries(value, position)
        ORDER BY position
    LOOP
        IF EXISTS (
            SELECT 1 FROM core.interview_questions
            WHERE module_id = target_module_id
              AND btrim(question) = btrim(item->>'question')
        ) THEN
            skipped_count := skipped_count + 1;
            CONTINUE;
        END IF;

        INSERT INTO core.interview_questions (
            module_id, question, answer, detailed_answer,
            follow_up_questions, difficulty, mastery_status, source_type
        ) VALUES (
            target_module_id,
            item->>'question', item->>'answer', NULL,
            item->>'followUpQuestions', item->>'difficulty', 'NOT_STARTED', 'MANUAL'
        )
        RETURNING id INTO new_question_id;

        FOR tag_name IN
            SELECT value FROM jsonb_array_elements_text(item->'tags')
        LOOP
            BEGIN
                SELECT id INTO STRICT target_tag_id
                FROM core.tags
                WHERE lower(btrim(name)) = lower(btrim(tag_name));
            EXCEPTION
                WHEN NO_DATA_FOUND THEN
                    INSERT INTO core.tags (id, name)
                    VALUES (gen_random_uuid(), tag_name)
                    RETURNING id INTO target_tag_id;
                    created_tag_count := created_tag_count + 1;
                WHEN TOO_MANY_ROWS THEN
                    RAISE EXCEPTION 'Multiple existing tags match "%"; import cancelled. Resolve the duplicate tag names first.',
                        tag_name;
            END;

            INSERT INTO core.interview_question_tags (question_id, tag_id)
            VALUES (new_question_id, target_tag_id);
            tag_relation_count := tag_relation_count + 1;
        END LOOP;
        imported_count := imported_count + 1;
    END LOOP;

    RAISE NOTICE 'Input: 10 main questions / 28 follow-ups. Inserted: %, skipped: %, tags created: %, tag relations inserted: %.',
        imported_count, skipped_count, created_tag_count, tag_relation_count;
END;
$agent_interview3_import$;

COMMIT;
