-- Source SHA-256: ea008fa227b5aede2d46b96b00f6222fab73a9e2a797c5c58672e6903300815b
-- One-time import from interview2.md; no AI analysis or content rewriting.
-- Execution guide: docs/SQL/数据库脚本使用说明.md.
-- 15 main questions / 46 follow-ups / 8 distinct tag names / 42 tag relations.
-- Target: exactly one existing ACTIVE Agent module.
-- Defaults: EASY / NOT_STARTED / MANUAL; detailed_answer is NULL.
-- Trailing heading parentheses supply tags, not part of the question title.
-- Reuse tags by case-insensitive trimmed name; create missing tags.
-- Ambiguous existing tag names cause a rollback, never an arbitrary match.
-- A heading without tag parentheses gets no tags.
-- Same trimmed question title in the same module: skip content AND tags.
-- Back up the database and run this WHOLE file in one connection.
-- Requires answer, follow_up_questions, source_type, and BIGINT identity question IDs.
-- No schema changes, document imports, indexing, AI calls, or external requests.

BEGIN;

SET LOCAL lock_timeout = '10s';

-- Serialize duplicate checks and missing-tag creation with other writes.
LOCK TABLE core.interview_questions, core.tags IN SHARE ROW EXCLUSIVE MODE;

DO $agent_interview2_import$
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
$interview2_payload$
[
  {
    "question": "MCP 的工作流程是什么？",
    "answer": "MCP 的核心工作流程是一个客户端-服务器协作的闭环，整体分为 7 个阶段：\n\n1）初始化连接：主机应用启动后，MCP Client 与 MCP Server 建立连接。一个主机可以同时连多个 Server，每个 Server 负责不同的工具和资源。\n\n2）获取工具列表：Client 从 Server 拉取可用的工具清单，包括每个工具的名称、参数、用途描述。这一步相当于\"能力注册\"，让模型知道手里有哪些牌可以打。\n\n3）构造 Function Calling 请求：用户输入问题后，Client 把工具描述和用户问题一起打包发给 LLM。传输格式是结构化的 Function Calling，告诉模型\"你现在能调用这些函数\"。\n\n4）模型智能决策：LLM 根据上下文和工具信息，判断是否需要调用外部工具、调用哪个、传什么参数。比如用户问天气，模型就会选择 getWeather 工具并填入城市参数。\n\n5）工具调用执行：如果模型决定调用工具，Client 把调用请求转发给 Server，Server 负责实际执行——跑脚本、查数据库、调 API，然后把结果返回。\n\n6）结果整合：工具执行结果传回 LLM，模型把这个结果和原始问题、对话上下文揉在一起，生成最终的自然语言回答。\n\n7）用户响应输出：Client 把模型生成的回答展示给用户，完成一次完整的人机协作。",
    "followUpQuestions": "{\"version\":1,\"items\":[{\"question\":\"MCP Client 和 Server 之间的连接断了怎么办？有没有重连机制？\",\"answer\":\"MCP 协议本身定义了连接生命周期管理。Stdio 模式下，Server 进程挂了 Client 会收到 EOF 信号，可以选择重启 Server 进程。SSE 模式下，HTTP 长连接断开后 Client 可以重新发起连接。具体的重连策略、重试次数、退避算法这些是 Client 实现层面的事，协议没有强制规定，得看各家 SDK 怎么实现。\"},{\"question\":\"如果模型一次需要调用多个工具，MCP 怎么处理？\",\"answer\":\"MCP 支持串行和并行两种模式。串行就是一个工具执行完拿到结果，再决定下一步调哪个。并行就是模型一次返回多个工具调用请求，Client 同时发给 Server 执行。具体用哪种取决于模型的推理结果和工具之间的依赖关系。比如先查用户 ID 再查订单，这种有依赖的只能串行；查天气和查股票这种独立的就可以并行。\"},{\"question\":\"MCP 和 LangChain 的 Agent 有什么关系？能一起用吗？\",\"answer\":\"两者解决的问题不在一个层面。LangChain Agent 是个编排框架，负责把多个步骤串起来、管理对话状态、处理工具调用结果。MCP 是个通信协议，定义 Client 和 Server 怎么交互。完全可以在 LangChain Agent 里接入 MCP Client，让 Agent 通过 MCP 调用外部工具。LangChain 社区已经有 MCP 的集成方案了。\"}]}",
    "tags": [
      "大模型",
      "AI",
      "MCP",
      "协议原理"
    ]
  },
  {
    "question": "MCP 协议安全性设计包含哪些层面？",
    "answer": "MCP 协议的安全性设计围绕三层防护展开：用户控制层、隔离沙箱层、传输验证层。\n\n1）用户同意和控制\n\n所有工具、资源、提示的访问都必须经过用户授权。Host 应用负责权限管理，在调用敏感操作前弹窗让用户确认。用户得清楚知道哪些数据会交给模型、每个工具能干什么，授权前心里有数。\n\n2）隔离与沙箱机制\n\n工具调用被封装在 MCP Server 内部，模型压根碰不到原始敏感数据。Server 充当中间层，即使模型被 prompt 注入攻击，攻击者也拿不到数据库密码、API Key 这些凭证。沙箱机制还能限制工具的执行环境，防止恶意代码跑飞。\n\n3）加密传输与来源验证\n\nMCP 内置请求验证机制，只有通过身份校验的请求才能访问资源。SSE 模式走 HTTPS，数据全程加密；Stdio 模式虽然是本地通信，但进程间也有隔离保护。",
    "followUpQuestions": "{\"version\":1,\"items\":[{\"question\":\"如果用户授权了一个工具，后续每次调用都要再确认吗？怎么平衡安全和体验？\",\"answer\":\"看操作的风险等级。读操作、查询类的授权一次就够了，后续调用不用反复确认。写操作、删除操作、涉及金钱的操作每次都要确认。可以在 Host 层做分级策略：低风险操作静默执行，中风险操作展示调用日志让用户知道发生了什么，高风险操作弹窗确认。Claude Desktop 现在就是这么做的，读文件不问，写文件会先展示要写的内容让用户确认。\"},{\"question\":\"MCP Server 被攻破了怎么办？有没有什么兜底方案？\",\"answer\":\"这就要靠纵深防御了。首先 Server 本身要做好加固，及时打补丁、最小权限原则。其次即使 Server 被攻破，损失也要可控：数据库账号只给必要的表权限，API Key 限制调用频率和 IP 白名单。再往上一层，Host 端做异常检测，比如某个 Server 突然返回大量敏感数据、调用频率异常飙升，自动熔断。最后是日志审计，事后能追溯攻击路径、评估影响范围。\"},{\"question\":\"Stdio 模式下 Client 直接 fork Server 进程，这种方式安全吗？\",\"answer\":\"Stdio 模式的安全前提是 Server 的 jar 包或可执行文件是可信的。Client 从配置文件读取 Server 的启动命令，直接 fork 执行。如果攻击者能篡改配置文件或替换 jar 包，就能执行任意代码。所以生产环境用 Stdio 模式要确保：配置文件权限只有 owner 可写、Server 可执行文件做完整性校验、部署流程有签名验证。更安全的做法是用 SSE 模式，Server 独立部署，Client 只知道 URL，攻击面小很多。\"}]}",
    "tags": [
      "大模型",
      "AI",
      "MCP",
      "安全性"
    ]
  },
  {
    "question": "如何将已有的应用转换成 MCP 服务？",
    "answer": "把现有应用转成 MCP 服务，核心就是把应用里的功能封装成标准化的 **MCP 工具**，再通过 MCP Server 暴露出去让大模型能调用。\n\n整个转换流程分这几步：\n\n1）梳理功能模块，把应用里需要暴露给外部的能力挑出来，比如 API 接口、数据查询、文件处理这些\n\n2）创建独立的 MCP 服务，跟原有业务服务隔离开，通过 HTTP API 或者 SDK 跟业务服务通信\n\n3）引入 MCP 依赖，Java 用 spring-ai-mcp，Python、TypeScript 等语言参考官方 SDK\n\n4）定义工具描述，包括功能说明、入参字段及描述、返回值字段及描述，这些信息会被大模型用来理解什么时候该调用这个工具\n\n5）实现 MCP Server，按照 MCP 协议规范构建服务端，负责接收 MCP 客户端请求、路由到对应功能模块、返回结果",
    "followUpQuestions": "{\"version\":1,\"items\":[{\"question\":\"MCP 服务用 HTTP 和用 stdio 通信有什么区别，怎么选？\",\"answer\":\"stdio 方式是本地进程间通信，MCP Client 直接启动 MCP Server 进程，通过标准输入输出交换数据。优点是零网络开销、部署简单，适合本地开发和桌面应用场景，Claude Desktop 就是用这种方式。HTTP 方式走网络协议，Server 独立部署，支持远程调用和多客户端并发。生产环境、微服务架构、需要集中管理的场景用 HTTP。一般本地工具用 stdio，线上服务用 HTTP。\"},{\"question\":\"如果 MCP Server 挂了或者响应超时，大模型那边会怎么处理？\",\"answer\":\"这个得看 MCP Client 的实现。一般会有超时机制，比如 30 秒没响应就返回错误。大模型拿到错误信息后，要么告诉用户工具调用失败了，要么换个方式回答。好的实现会做重试和熔断，避免一个工具故障拖垮整个对话。实际开发中建议在 MCP Server 侧做好健康检查和优雅降级。\"},{\"question\":\"一个 MCP Server 能注册多少个工具，有上限吗？\",\"answer\":\"协议本身没有硬性限制，但工具太多会有问题。大模型需要理解每个工具的描述来决定调用哪个，工具列表太长会占用大量 context window，还可能导致模型选错工具。实践中建议一个 Server 控制在 10-20 个工具以内，功能相近的可以合并，差异大的拆成多个 Server 按领域划分。\"}]}",
    "tags": [
      "大模型",
      "AI",
      "MCP",
      "开发实践"
    ]
  },
  {
    "question": "什么是 Manus？说说你对它的了解",
    "answer": "Manus 是咱们中国团队 Monica.im 在 2025 年 3 月推出的全球首款通用型 AI 智能体，最大的特点是能独立思考、规划并执行复杂任务，直接交付完整成果，不是那种只会聊天给建议的 AI。\n\n比如你让它做股票分析，它会自己去抓数据、跑分析、生成报告，最后给你一份完整的 PDF，不需要你一步步指挥。简历筛选、市场调研这些任务也是同样的逻辑，扔给它一个目标，它自己想办法搞定。\n\n底层实现是通过一个中央调度模块，把用户的高层指令拆解为多个子任务，再分配给不同的内部 Agent 或工具执行，形成端到端的自动化流程。规划和决策还是靠大模型，比如 Claude、Qwen 这些。",
    "followUpQuestions": "{\"version\":1,\"items\":[{\"question\":\"Manus 底层调用多个大模型，怎么决定哪个子任务用哪个模型？\",\"answer\":\"主要靠任务类型匹配。规划器在拆解任务时会给每个子任务打标签，比如\\\"文字创作\\\"、\\\"代码生成\\\"、\\\"多语言翻译\\\"这些，然后根据标签路由到最适合的模型。Claude 擅长长文写作和推理，Qwen 多语言能力强，代码任务可能走专门的代码模型。这个路由策略可以配置，也可以让规划器自己学习哪个模型在哪类任务上表现好。\"},{\"question\":\"Manus 执行过程中出错了怎么办？有没有容错机制？\",\"answer\":\"有几层容错。第一层是子任务级别的重试，某个子任务失败了会自动重试 2-3 次，可能换个 prompt 或者换个工具再试。第二层是方案切换，如果某条路径反复失败，规划器会重新规划，绕过这个子任务或者用替代方案。第三层是人工介入，Manus 支持设置关键节点的人工审核，出问题了可以暂停等人决策。全程的执行 Trace 都有记录，事后可以回放分析哪里出了问题。\"},{\"question\":\"这种通用智能体和 LangGraph 这类编排框架有什么关系？\",\"answer\":\"可以把 Manus 理解为 LangGraph 思想的产品化落地。LangGraph 是开发框架，提供图结构编排、状态管理这些能力，开发者拿它来构建 Agent 应用。Manus 是面向终端用户的产品，底层很可能用了类似的编排架构，但做了大量的工程化封装，包括 Web 界面、任务回放、异步执行、多模型调度这些。一个是给开发者的工具，一个是给普通用户的产品。\"}]}",
    "tags": [
      "大模型",
      "AI",
      "Manus"
    ]
  },
  {
    "question": "Computer Use 是什么？说说它的原理",
    "answer": "Computer Use 是 Anthropic 在 Claude 3.5 Sonnet 中推出的一项能力，让 AI 能够直接操作计算机，通过模拟鼠标点击、键盘输入等方式与操作系统和软件交互，实现从“文字对话”到“实际操作”的跨越。\n\n核心原理主要包含三个层面：\n\n1）通过操作系统级 API 把自然语言指令转化为计算机可执行的操作。比如用户说“打开 Chrome 搜索面试鸭”，AI 就会调用 Windows API 或 macOS 系统调用启动浏览器并输入关键词。\n\n2）内置多智能体协作机制，包括任务规划代理负责分解任务、工具调用代理负责执行操作、验证代理负责校验结果，形成流水线式处理。比如“生成报告”这个指令会被拆分为数据获取、图表绘制、格式校验等子任务。\n\n3）利用 OCR 技术识别屏幕内容，结合语义理解定位目标。比如“点击页面右上角的‘登录’按钮”，AI 会先分析页面结构，找到按钮位置，再模拟点击。",
    "followUpQuestions": "{\"version\":1,\"items\":[{\"question\":\"Computer Use 跟传统的 RPA 有什么区别？\",\"answer\":\"最大的区别在于灵活性。传统 RPA 需要预先录制固定流程，界面稍微变一下就容易出错。Computer Use 靠 AI 理解语义和识别屏幕内容，不需要提前定义每一步操作，能自适应界面变化。另外 RPA 通常只能处理结构化的、重复性的任务，Computer Use 能处理更复杂的、需要推理判断的任务。\"},{\"question\":\"安全性方面有什么考虑？让 AI 操作电脑不怕出问题吗？\",\"answer\":\"确实有风险，所以一般会有几层防护。首先是权限隔离，Computer Use 只能访问特定的应用和资源，不会拿到系统管理员权限。其次是操作审计，所有操作都会被记录，方便事后追溯。还有就是验证代理会在关键节点校验结果，发现异常可以暂停并请求人工干预。Anthropic 官方也建议在沙箱环境中使用，避免直接在生产系统上跑。\"},{\"question\":\"如果屏幕上有多个相似的按钮，AI 怎么确定点哪个？\",\"answer\":\"AI 会分析用户指令中的描述，比如“右上角的”“绿色的”“带购物车图标的”，然后结合 OCR 识别出的文字、元素位置、视觉特征进行匹配。如果还是分不清，可能会请求用户进一步澄清，或者按照默认优先级处理，比如优先选择可见区域内的第一个匹配项。\"}]}",
    "tags": [
      "大模型",
      "AI"
    ]
  },
  {
    "question": "ReAct 是什么？说说它的原理",
    "answer": "ReAct 是 Reasoning and Acting 的缩写，是一种基于大语言模型的智能体框架，核心思想是让模型在生成回答时交替输出“思考”和“行动”步骤，边想边做来完成复杂任务。\n\n整个流程是这样的：\n\n1）模型先通过自然语言生成思考过程，明确是否需要工具、需要什么工具、输入什么参数\n\n2）根据当前思路选择合适的操作，比如调用搜索引擎、请求 API、跟环境交互等，并输出具体的指令\n\n3）执行完 Action 后，系统把返回的结果给模型，这一步叫 观察（Observation）\n\n4）把 Observation 附加到上下文，模型在新的上下文中继续“思考→行动→观察”循环，直到输出最终答案\n\n和传统的链式思维 CoT 相比，CoT 只能线性推导，一条路走到黑。ReAct 允许在行动失败后重试或切换方案，灵活性高很多。",
    "followUpQuestions": "{\"version\":1,\"items\":[{\"question\":\"ReAct 和 Function Calling 有什么关系？\",\"answer\":\"ReAct 是一种框架思想，Function Calling 是具体的实现机制。ReAct 定义了“思考→行动→观察”的循环模式，而 Function Calling 是 OpenAI 等厂商提供的能力，让模型能以结构化的方式调用外部函数。可以用 Function Calling 来实现 ReAct 中的 Action 步骤，两者是配合关系，不是替代关系。\"},{\"question\":\"ReAct 循环不收敛怎么办？模型一直在那转圈？\",\"answer\":\"几个办法：首先设最大迭代次数，比如 10 轮还没结果就强制结束。其次优化工具描述和 few-shot 示例，让模型更清楚什么时候该调用 Finish 结束任务。还可以加一些启发式规则，比如连续 3 次 Observation 内容相似，就判定为陷入循环，强制跳出。\"},{\"question\":\"ReAct 的 Thought 部分对用户可见吗？生产环境一般怎么处理？\",\"answer\":\"看业务需求。如果是面向开发者的工具，可以把 Thought 展示出来，方便调试和理解模型的决策过程。如果是面向普通用户的产品，一般会隐藏掉，只展示最终答案。有些产品会折中处理，把 Thought 简化成“正在搜索...”“正在分析...”这种进度提示。\"}]}",
    "tags": [
      "大模型",
      "AI"
    ]
  },
  {
    "question": "在大模型应用中，通常如何实现长短期记忆机制？",
    "answer": "大模型的记忆机制分两块：短期记忆靠上下文窗口，长期记忆靠外部存储。\n\n短期记忆的实现方式主要有三种：\n\n1）直接缓存，把最近的对话全部保留在上下文里。比如 GPT-4 的 400k tokens 窗口、Gemini 3 Pro 的 100 万（1M）tokens 窗口，能装多少就装多少\n\n2）滑动窗口，只保留最近 N 轮对话，比如只保留最近 5 轮，老的对话直接丢掉\n\n3）Token 限制，按 Token 数量截断历史，比如只保留最近 12k tokens\n\n长期记忆就得靠外部存储了：\n\n1）摘要压缩，对历史内容生成摘要，比如每 10 轮对话压缩成 1 段，模仿人类记忆的“模糊记忆”机制\n\n2）向量数据库，把历史信息存入 Milvus、Pinecone 这类向量库，通过语义检索动态召回相关片段，这就是 RAG 的思路\n\n3）知识图谱，构建实体关系图，支持结构化查询\n\n从目前的实现来看，短期记忆依赖模型原生能力，长期记忆通过外部存储实现检索。",
    "followUpQuestions": "{\"version\":1,\"items\":[{\"question\":\"摘要压缩会不会丢失重要信息？怎么保证摘要质量？\",\"answer\":\"确实会丢信息，摘要本质上是有损压缩。保证质量的办法有几个：用专门微调过的摘要模型而不是通用模型；对关键实体做特殊标记，摘要时强制保留；分层摘要，近期的细一点，远期的粗一点；还可以保留原始记录的索引，需要时回溯原文。\"},{\"question\":\"向量检索和关键词检索在记忆系统里怎么选？\",\"answer\":\"看场景。向量检索擅长语义匹配，用户换个说法也能检索到相关内容，但对精确匹配的场景效果差，比如用户问“我的订单号是多少”，向量检索可能召回一堆订单相关但不是用户自己订单的内容。关键词检索精确但死板。实际项目中一般混合用，先用关键词过滤一遍，再用向量排序。\"},{\"question\":\"记忆系统怎么做多租户隔离？不同用户的记忆不能串了吧？\",\"answer\":\"肯定不能串。向量数据库层面，给每个用户的记忆打上 user_id 标签，检索时加过滤条件。应用层面，记忆的 key 要带用户标识。如果用 Redis 之类的缓存，可以用不同的 namespace 或者 key 前缀隔离。安全要求高的场景，甚至可以物理隔离，不同用户的数据存不同的库。\"}]}",
    "tags": [
      "大模型",
      "AI"
    ]
  },
  {
    "question": "什么是大模型 Agent？它与传统的 AI 系统有什么不同？",
    "answer": "大模型 Agent 是以大语言模型为大脑，配合规划、记忆、工具调用等模块，能够自主完成复杂任务的系统。它和传统 AI 最大的区别在于：传统 AI 是你问一句它答一句，Agent 是你给它一个目标，它自己拆任务、调工具、一步步干完。\n\n具体差异可以从这几个维度来看：\n\n1）目标导向 vs 被动响应。传统大模型就是个对话机器，用户输入什么它输出什么，没有主动性。Agent 不一样，你给它一个最终目标，比如“帮我调研竞品并写一份分析报告”，它会自己规划要做哪些步骤、先做什么后做什么，然后一步步执行。\n\n2）有记忆 vs 无状态。传统模型只能看到当前对话的上下文窗口，聊完就忘。Agent 有专门的记忆模块，短期记忆存当前任务的中间状态，长期记忆存历史经验，下次碰到类似问题可以复用。\n\n3）能调用工具 vs 只能说话。传统模型只能输出文本，想搜索、算数、操作数据库都得人来做。Agent 能调用搜索引擎、执行代码、读写文件、调 API，真正把事情做出来。\n\n4）多步推理 vs 单轮问答。Agent 能在执行过程中不断评估结果、发现问题、调整策略，形成一个闭环。传统 AI 系统要么靠写死的规则引擎，要么就是一锤子买卖。",
    "followUpQuestions": "{\"version\":1,\"items\":[{\"question\":\"ReAct 和 Plan-and-Execute 两种 Agent 范式有什么区别？实际项目中怎么选？\",\"answer\":\"ReAct 是边想边做，每一步先推理要做什么、为什么，然后执行，看到结果再决定下一步。优点是灵活，能根据中间结果随时调整；缺点是容易跑偏，任务一复杂就会丢失方向。Plan-and-Execute 是先整体规划出完整的步骤列表，再逐个执行。优点是稳定，不容易偏离目标；缺点是不够灵活，如果中间某步失败需要重新规划。实际选择看任务特点：探索性强、结果不确定的用 ReAct；目标明确、步骤可预期的用 Plan-and-Execute；复杂任务可以混用，外层 Plan-and-Execute 保证方向，内层 ReAct 处理细节。\"},{\"question\":\"Agent 的记忆模块怎么设计？长期记忆和短期记忆分别用什么技术实现？\",\"answer\":\"短期记忆最简单的做法就是把对话历史和中间结果拼到 Prompt 里，受限于模型的上下文窗口。更好的做法是用 Scratchpad，把关键信息结构化存储，只在需要时取出来。长期记忆一般用向量数据库，把历史经验、知识片段 Embedding 之后存进去，检索时算相似度取 Top-K。常用的向量库有 Pinecone、Milvus、Chroma、Weaviate。设计上要注意记忆的增删改查策略，不能无限堆积，需要定期压缩或淘汰不重要的内容。\"},{\"question\":\"怎么解决 Agent 多步执行时的错误累积问题？\",\"answer\":\"几个常见的思路：一是加强单步准确率，用更强的模型、更好的 Prompt、更精准的工具；二是加检查点，每步执行完校验结果是否合理，不合理就重试或回退；三是引入自我反思机制，让 Agent 定期回顾执行历史，发现偏离目标就及时纠正；四是限制自主性，关键操作要人工确认，不让 Agent 完全自动跑。实际生产中这几种方法一般结合使用。\"}]}",
    "tags": [
      "大模型",
      "AI",
      "Agent"
    ]
  },
  {
    "question": "LLM Agent 的基本架构有哪些组成部分？",
    "answer": "LLM Agent 的核心架构由四个部分组成：LLM 核心、规划模块、记忆模块、工具模块。\n\n1）LLM 核心是整个系统的大脑，负责理解任务、生成计划、协调各模块工作。GPT、Claude、Qwen 这些大模型都可以充当这个角色。\n\n2）规划模块负责把复杂目标拆成可执行的子任务。比如“帮我写一篇市场分析报告”这种任务，规划模块会拆成：搜集行业数据、分析竞品、整理趋势、撰写报告等步骤。执行过程中还能根据结果动态调整计划。\n\n3）记忆模块分短期和长期两种。短期记忆保存当前任务的上下文和中间状态，长期记忆存储跨任务的知识和经验。没有记忆模块，Agent 干完上一步就忘了，没法处理多轮复杂任务。\n\n4）工具模块是 Agent 的手和脚，让它能和外部世界交互。搜索引擎、代码解释器、数据库、API 接口都可以作为工具接入。没有工具，Agent 只能动嘴不能动手。",
    "followUpQuestions": "{\"version\":1,\"items\":[{\"question\":\"Agent 的规划模块怎么处理计划执行失败的情况？\",\"answer\":\"主要有三种策略。一是重试，简单粗暴但对瞬时故障有效。二是回退重规划，把失败的步骤和原因反馈给规划模块，重新生成后续计划。三是引入 Reflexion 机制，让 Agent 分析失败原因、总结教训，然后带着新认知重新尝试。实际项目中一般组合使用，先重试 2-3 次，不行就触发重规划，重要任务还会存下失败案例供后续学习。\"},{\"question\":\"记忆模块用向量数据库存储时，怎么决定存什么、什么时候存、存多久？\",\"answer\":\"存什么主要看信息的重要程度和复用价值，任务的最终结果、关键决策点、失败教训这些值得存。什么时候存一般是任务完成时批量写入，也可以设置阈值触发增量存储。存多久需要淘汰策略，常见的有按时间淘汰、按访问频率淘汰、按相关度评分淘汰。还要考虑去重和压缩，相似的记忆合并成一条，节省存储也减少检索噪声。\"},{\"question\":\"工具太多的时候模型选错工具怎么办？\",\"answer\":\"首先是减少候选工具数量，不要一股脑全都塞进去，根据当前任务类型动态筛选相关工具。然后是优化工具描述，写清楚每个工具的用途、适用场景、参数含义，让模型能准确判断。还可以加一层工具路由，先用一个轻量模型或规则引擎粗筛，再让主模型在小范围内精选。最后是加校验环节，工具调用前检查参数合法性，调用后验证结果是否合理，不合理就让模型重新选择。\"}]}",
    "tags": [
      "大模型",
      "AI",
      "Agent"
    ]
  },
  {
    "question": "LLM Agent 常见功能有哪些？",
    "answer": "LLM Agent 的核心功能可以从感知、思考、行动三个层面来理解：\n\n1）指令理解与上下文处理：通过自然语言解析用户指令，理解任务目标，抓取对话历史中的关键信息。\n\n2）任务规划与分解：拿到一个复杂需求后，Agent 会先拟定解决问题的多个步骤，再按顺序逐步执行。比如用户问“帮我对比三款笔记本的性价比”，Agent 会自动拆成搜索产品信息、提取关键参数、计算性价比、生成对比表格这几步。\n\n3）外部工具调用：Agent 不只会“说”，还会“做”。它能调用搜索引擎查实时信息、调计算器算数、调数据库取数据、调各种 API 完成特定任务。OpenAI 的 Function Calling、LangChain 的 Tools 都是这个能力的具体实现。\n\n4）循环观测与执行：每次调用工具后，观测模块会收集执行结果并反馈给决策模块，形成持续的 Action-Observation 循环，不断微调策略直到任务完成。\n\n5）记忆管理：短期记忆负责当前对话的上下文，长期记忆负责存储历史交互和学到的知识，两者配合让 Agent 在多轮交互中保持一致性。\n\n6）决策与分支：根据观测结果和已有记忆灵活做出决策，处理 if-else 条件分支和循环逻辑，执行路径能动态调整，不会一条路走到黑。\n\n7）检索增强生成：遇到知识盲区时，Agent 会调用外部知识库或文档，以 RAG 的方式补充信息，避免一本正经地胡说八道。\n\n8）自我反思与纠错：通过内省模块评估自身输出的正确性，发现偏差时触发重试或修正策略。Reflexion、Self-Refine 这些方法就是干这个的。\n\n9）多模态能力：现在的 Agent 不只处理文本，还能看图、听音频、生成图像，应用场景大大扩展。",
    "followUpQuestions": "{\"version\":1,\"items\":[{\"question\":\"Agent 调用工具失败了怎么办？比如 API 超时或者返回格式不对？\",\"answer\":\"一般有几层防护。第一层是重试机制，失败后指数退避重试 2-3 次。第二层是错误信息反馈，把报错信息塞回给 LLM，让它换个方式调用或者换个工具。第三层是兜底策略，实在搞不定就承认“这个我现在做不了”，别瞎编结果。LangChain 的 Agent Executor 里有现成的 max_iterations 和 handle_parsing_errors 参数控制这些行为。\"},{\"question\":\"多个 Agent 协作的场景，怎么保证它们之间的通信和协调？\",\"answer\":\"主流做法有两种。一种是中心化调度，搞一个 Orchestrator Agent 负责分配任务、汇总结果，其他 Agent 只管执行自己那摊事。AutoGen 框架就是这个思路。另一种是去中心化的消息传递，每个 Agent 有自己的收件箱，通过发消息协作，类似微服务架构。CrewAI 支持这种模式。选哪种取决于任务的复杂度和对实时性的要求。\"},{\"question\":\"Agent 的记忆越来越大，检索效率下降怎么优化？\",\"answer\":\"几个方向可以搞。一是分层存储，热数据放内存，温数据放向量库，冷数据归档或删除。二是记忆压缩，把多轮对话总结成一段话存起来，而不是存原始记录。三是检索策略优化，不只按相似度检索，还可以加时间衰减、重要性打分。MemGPT 的做法是把记忆分成 main context 和 archival storage 两层，按需换入换出。\"}]}",
    "tags": []
  },
  {
    "question": "Agent智能体的工作过程是怎样的？",
    "answer": "Agent 的工作过程本质上是一个 **感知-思考-行动** 的循环，可以拆成这么几步：\n\n1）接收与理解输入：Agent 拿到用户的自然语言指令后，先把它解析成内部隐式的格式，搞清楚用户到底想干啥、有什么约束条件。\n\n2）规划：LLM 根据输入生成一份行动计划，把大任务拆成可执行的小步骤并排好序。比如用户说“帮我订明天北京到上海的机票”，Agent 会拆成查航班、筛选合适的、确认价格、完成预订这几步。\n\n3）工具调用：按照计划，Agent 挑选合适的外部工具来干活。查航班可能调携程 API，算价格用计算器，存订单信息用数据库。这一步是 Agent 区别于普通聊天机器人的关键能力。\n\n4）观测：工具执行完会返回结果，Agent 把这个结果作为 Observation 反馈给 LLM，更新当前状态。如果查航班返回“没有直飞”，Agent 就知道该调整策略了。\n\n5）记忆：关键的观测结果和交互细节会被存进短期或长期记忆，保证多轮对话时不会“失忆”。\n\n6）决策：综合最新的观测结果、记忆内容和任务目标，Agent 判断下一步该干啥。任务完成就准备输出，没完成就继续执行下一个子任务，遇到问题就重新规划。\n\n7）输出：所有步骤跑完后，Agent 汇总信息，生成最终结果返回给用户。\n\n8）反思与纠错：高级 Agent 还会启动自我反思模块，回顾执行过程，评估结果是否正确，发现问题就重规划或修正策略，避免下次犯同样的错。",
    "followUpQuestions": "{\"version\":1,\"items\":[{\"question\":\"Agent 陷入死循环怎么办？比如一直在重复调用同一个工具？\",\"answer\":\"几层防护。第一层设 max_iterations，跑超过 15-20 轮强制停止。第二层检测重复，连续 3 次调用相同工具且参数一样，直接中断并报错。第三层是 token 预算，总消耗超过阈值就停下来。LangGraph 里可以在 checkpointer 里配置这些策略，AutoGen 也有类似的 termination_condition 参数。\"},{\"question\":\"如果一个任务需要调用多个工具，怎么决定调用顺序？\",\"answer\":\"两种主流方案。一种是让 LLM 自己决定，ReAct 模式下每次 Thought 时模型会分析当前状态、推理出下一步该调哪个工具。另一种是预定义 workflow，用 DAG 图描述工具之间的依赖关系，比如“先查用户信息，再查订单，最后发通知”，按图执行。后者确定性更强，适合业务流程固定的场景。\"},{\"question\":\"Agent 的推理过程很长，用户等不及怎么办？\",\"answer\":\"可以用流式输出，每一步 Thought 和 Action 都实时推给前端，让用户看到 Agent 在干嘛。另外把耗时的工具调用做成异步，比如查数据库的时候同时展示“正在查询中...”。还可以做任务拆分，先快速给个初步结果，复杂的部分后台慢慢跑完再补充。\"}]}",
    "tags": [
      "大模型",
      "AI",
      "Agent"
    ]
  },
  {
    "question": "如何让 LLM Agent 具备长期记忆能力？",
    "answer": "LLM 原生的上下文窗口只有几千到十几万 tokens，聊多了前面的内容就被“挤出去”了，所以要给 Agent 加长期记忆，必须借助外部机制。\n\n主流方案有两种：\n\n1）**向量数据库 + RAG**：把历史对话和知识转成向量 embeddings，存进 Pinecone、ChromaDB、Milvus 这类向量库。新会话来了，先根据用户输入检索相关的历史内容，把检索结果塞进 prompt，LLM 就能“回忆”起之前聊过什么。这是目前最成熟、落地最多的方案。\n\n2）**分层记忆体系**：把记忆分成短期和长期两层。短期记忆就是当前会话的上下文，长期记忆是压缩后的关键信息摘要或 embeddings。MemGPT 就是这个思路，它借鉴操作系统的虚拟内存机制，把记忆分成 main context 和 archival storage，按需换入换出。",
    "followUpQuestions": "{\"version\":1,\"items\":[{\"question\":\"向量检索召回的内容不相关怎么办？比如用户问的是 A 话题，结果召回了 B 话题的历史记录？\",\"answer\":\"几个优化方向。一是优化 embedding 模型，换用针对特定领域微调过的模型，或者用更强的通用模型比如 text-embedding-3-large。二是加元数据过滤，给每条记忆打上时间、话题、用户意图等标签，检索时先按元数据筛选再做向量匹配。三是用 rerank 模型，初筛出 top-20 后再用 cross-encoder 精排一遍。四是混合检索，同时用关键词匹配和向量检索，两路结果融合。\"},{\"question\":\"记忆数据量太大，检索变慢了怎么处理？\",\"answer\":\"先看瓶颈在哪。如果是向量库本身慢，换成支持 GPU 加速的方案比如 Milvus，或者用 HNSW、IVF 这类 ANN 索引加速。如果是数据量确实太大，做分层存储，最近 7 天的记忆放高性能存储，更早的归档到便宜存储，检索时先查热数据。还可以做记忆压缩，定期把多轮对话总结成一段摘要，用摘要替代原始记录。\"},{\"question\":\"用户要求删除他的所有历史记忆，技术上怎么实现？\",\"answer\":\"向量库通常支持按 ID 或 metadata 删除。设计时每条记忆都要绑定 user_id，删除时按 user_id 过滤出所有相关条目批量删除。要注意的是，如果用了缓存层，缓存里的数据也得清掉。另外删除操作要做好审计日志，合规检查时能证明确实删干净了。如果用的是 Pinecone 这种托管服务，它们一般有 namespace 机制，每个用户一个 namespace，删除时直接删整个 namespace 最干净。\"}]}",
    "tags": [
      "大模型",
      "AI",
      "Agent"
    ]
  },
  {
    "question": "LLM Agent 如何进行动态 API 调用？",
    "answer": "让 LLM Agent 动态调用外部 API，核心思路是把各种 API 封装成\"工具\"提供给模型，让模型根据用户意图自动选择并执行。主流方案有三种：\n\n1）Function Calling：这是目前最常用的方式。事先定义好函数接口，包括函数名、参数格式、返回值结构，模型在生成响应时如果判断需要调用某个函数，就会输出符合该接口的 JSON，后端框架解析后触发真实 API 调用，拿到结果再返回给模型继续生成。\n\n2）插件机制：在平台中注册好各类 API，把它们封装成插件或工具。当模型检测到用户需要某项功能时，自动加载对应插件并发起调用。OpenAI Plugin、LangChain Agents 都是这个思路。\n\n3）代码解释器：部署一个受限的 Python 沙箱环境，当模型需要做计算、数据处理或调用第三方库时，会生成代码片段在沙箱中执行，再将结果反馈给用户。\n\n\n\n以 OpenAI 的 Function Calling 为例，调用流程大概是这样的：\n\n```python\nimport openai\n\n# 1. 定义可用的工具\ntools = [{\n    \"type\": \"function\",\n    \"function\": {\n        \"name\": \"get_weather\",\n        \"description\": \"获取指定城市的天气信息\",\n        \"parameters\": {\n            \"type\": \"object\",\n            \"properties\": {\n                \"city\": {\"type\": \"string\", \"description\": \"城市名称\"}\n            },\n            \"required\": [\"city\"]\n        }\n    }\n}]\n\n# 2. 发送请求，模型判断是否需要调用工具\nresponse = openai.chat.completions.create(\n    model=\"gpt-4-turbo\",\n    messages=[{\"role\": \"user\", \"content\": \"北京今天天气怎么样？\"}],\n    tools=tools\n)\n\n# 3. 如果模型返回 tool_calls，解析并执行真实 API\nif response.choices[0].message.tool_calls:\n    tool_call = response.choices[0].message.tool_calls[0]\n    # 解析参数，调用真实的天气 API，拿到结果后继续对话\n\n```",
    "followUpQuestions": "{\"version\":1,\"items\":[{\"question\":\"如果模型调用 API 失败了，比如超时或者返回错误，Agent 应该怎么处理？\",\"answer\":\"一般有三种策略。一是重试机制，设置最大重试次数和退避间隔，网络抖动或临时故障可以自愈。二是降级处理，比如天气 API 挂了就返回“暂时无法获取天气信息”，别让整个对话卡死。三是把错误信息塞回对话历史让模型自己决策，有时候模型会换个思路用别的工具解决问题。\"},{\"question\":\"Function Calling 的参数提取有时候会出错，比如用户说“明天北京”，模型可能漏掉日期参数，怎么优化？\",\"answer\":\"几个方向。一是把参数描述写得更详细，在 description 里举例说明什么情况下需要填什么值。二是用 required 字段强制要求必填参数，模型会更积极地从上下文里挖。三是加一层校验，参数不完整就追问用户补充。四是在 system prompt 里强调“如果信息不足要主动询问”。\"},{\"question\":\"一个 Agent 注册了几十个工具，模型会不会选错或者干脆不知道选哪个？\",\"answer\":\"工具太多确实会影响选择准确率。常用的优化手段是分层路由，先用一个轻量模型或规则判断用户意图属于哪个类别，再把这个类别下的工具子集给到主模型。另一个思路是动态加载，根据对话主题只激活相关的几个工具，别一股脑全塞进去。工具的 name 和 description 也很关键，写清楚适用场景能显著提升命中率。\"}]}",
    "tags": [
      "大模型",
      "AI",
      "Agent"
    ]
  },
  {
    "question": "LLM Agent 在多模态任务中如何执行推理？",
    "answer": "LLM Agent 在多模态推理中，核心是先把不同模态的数据转换成统一的向量表示，再将这些表示注入到大模型进行跨模态融合和推理。主流方案有三种：\n\n1）视觉-语言融合：用 CLIP、BLIP-2 等视觉编码器将图像转为 embeddings，然后与文本输入一起提供给 LLM。或者直接使用 GPT-4o 这种内置视觉理解能力的模型，一站式处理图像并输出自然语言回答。\n\n2）语音-文本桥接：用 Whisper 等 ASR 模型将音频转换成文本，再交给 LLM 分析和生成响应。这种串联方式简单直接，工程上容易落地。\n\n3）工具链调用：LLM Agent 根据任务需求按需调用 OCR、物体检测、视频帧提取等工具，将结果合并进上下文，再由模型执行更高级别的推理或决策。\n\n以 GPT-4o 为例，多模态调用大概是这样：\n\n```python\nimport openai\nimport base64\n\n# 读取图片并转为 base64\nwith open(\"image.jpg\", \"rb\") as f:\n    image_data = base64.standard_b64encode(f.read()).decode(\"utf-8\")\n\nresponse = openai.chat.completions.create(\n    model=\"gpt-4o\",\n    messages=[{\n        \"role\": \"user\",\n        \"content\": [\n            {\"type\": \"text\", \"text\": \"这张图里有什么？\"},\n            {\"type\": \"image_url\", \"image_url\": {\"url\": f\"data:image/jpeg;base64,{image_data}\"}}\n        ]\n    }]\n)\n\n```",
    "followUpQuestions": "{\"version\":1,\"items\":[{\"question\":\"CLIP 的对比学习具体是怎么做的？为什么能让图像和文本在同一个空间里？\",\"answer\":\"CLIP 用的是 InfoNCE loss。训练时每个 batch 里有 N 对图像-文本，模型要学会把配对的图像和文本 embedding 拉近，把不配对的推远。具体来说，对于每张图，它的正样本是对应的文本描述，负样本是 batch 里其他 N-1 条文本。反过来对文本也一样。训练完成后，图像编码器和文本编码器虽然结构不同，但输出的向量天然就在同一个语义空间里，可以直接算余弦相似度。\"},{\"question\":\"视频太长，关键帧抽取会丢失信息，有没有更好的处理方式？\",\"answer\":\"几个方向。一是用更智能的抽帧策略，比如基于场景切换检测，只在内容变化明显的地方抽帧，避免冗余。二是用视频编码器而不是图像编码器，比如 VideoMAE，它能直接处理连续帧序列，捕捉时序动态。三是分层处理，先用轻量模型做视频摘要，提取关键片段，再对关键片段做精细分析。四是如果视频实在太长，可以让用户指定时间段，或者用 LLM 先看一遍粗略摘要再决定重点看哪里。\"},{\"question\":\"多模态模型的幻觉问题怎么缓解？\",\"answer\":\"几个常用手段。一是在 prompt 里明确要求模型“只描述图中确实存在的内容，不要推测”。二是用 grounding 技术，让模型输出时标注每个描述对应图中的哪个区域，强制它建立图文对应关系。三是后处理校验，把模型输出再送给一个专门的检测模型，验证描述的物体是否真的存在于图中。四是用 Chain of Thought，让模型分步“先识别、再描述、最后总结”，每一步都可审计。\"}]}",
    "tags": [
      "大模型",
      "AI",
      "Agent"
    ]
  },
  {
    "question": "市面上有哪些主流的 LLM Agent 框架？各自的特点是什么？",
    "answer": "目前主流的 LLM Agent 框架各有侧重，按定位可以分为三类：\n\n**应用开发类**：LangChain 和 LlamaIndex。LangChain 模块化设计，支持多模型接入、链式调用、工具集成和记忆管理，适合快速构建复杂的 LLM 应用。LlamaIndex 专注于数据索引和检索，优化 LLM 与外部数据的结合，做 RAG 场景首选。\n\n**自主 Agent 类**：AutoGPT 是早期自主 Agent 代表项目，能够根据用户目标自动拆解任务、自主迭代，适用于需要自主决策的场景。\n\n还有个玩具项目：BabyAGI，是最小化的自主 Agent，轻量好部署。\n\n**多智能体协作类**：CrewAI 和 LangGraph。CrewAI 支持多个 Agent 组成团队，不同 Agent 负责不同角色，适用于复杂工作流。LangGraph 基于有向图（支持循环和条件边）管理 LLM 工作流，支持并行执行和状态持久化，更可控更易扩展（注意：LangGraph 本身就是 LangChain 的子项目）。",
    "followUpQuestions": "{\"version\":1,\"items\":[{\"question\":\"LangChain 的核心设计\",\"answer\":\"LangChain 是目前生态最完善的 LLM 应用框架，核心模块有四个：\\n\\n1）Chains：把多个步骤串起来，支持 CoT 等多步推理模式。比如先让模型理解问题，再让模型生成 SQL，最后执行 SQL 返回结果\\n\\n2）Tools：整合数据库、API、搜索引擎等外部能力，模型可以根据需要调用这些工具\\n\\n3）Memory：支持长期会话记忆，对话历史可以存到 Redis、PostgreSQL 里，下次对话还能接上\\n\\n4）Agents：动态决策引擎，模型自己判断该调用什么工具、按什么顺序执行\"},{\"question\":\"LangChain 的 Agent 和普通的 Chain 有什么区别？什么时候该用 Agent？\",\"answer\":\"Chain 是固定流程，写代码时就定死了先执行什么后执行什么。Agent 是动态决策，模型自己判断该调用哪个工具、按什么顺序。如果任务流程确定，比如“先查数据库再格式化输出”，用 Chain 就够了，执行快、可预测。如果任务不确定，比如用户可能问天气也可能问股票，需要根据问题动态选择工具，就得用 Agent。Agent 的代价是每次都要让模型做一次决策，延迟和 token 消耗都更高。\"},{\"question\":\"AutoGPT 这种全自主 Agent 在生产环境有落地案例吗？主要的坑是什么？\",\"answer\":\"直接用 AutoGPT 的生产案例很少，主要问题不可控。一是容易跑偏，给它一个目标，它可能会拆解出一堆不相关的子任务，消耗大量 token 还没产出。二是没有边界意识，可能会调用不该调用的 API，访问不该访问的数据。三是调试困难，中间过程是个黑箱，出了问题很难定位。实际落地的都是“受控的自主”，关键节点人工审批，执行范围严格限制，有熔断机制防止失控。\"},{\"question\":\"如果要从零搭一个 RAG 系统，LangChain 和 LlamaIndex 选哪个？\",\"answer\":\"看团队情况。如果团队熟悉 LangChain 生态，用 LangChain 的 RAG 模块也能做，好处是一套框架搞定所有事。如果 RAG 是核心需求，LlamaIndex 更专业，它在文档解析、分块策略、检索优化上做得更深。还有一种常见做法是两个一起用，用 LlamaIndex 做数据索引和检索，用 LangChain 做上层的 Agent 编排和对话管理，各取所长。\"}]}",
    "tags": [
      "大模型",
      "AI",
      "Agent"
    ]
  }
]
$interview2_payload$::JSONB) WITH ORDINALITY AS entries(value, position)
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
            item->>'followUpQuestions', 'EASY', 'NOT_STARTED', 'MANUAL'
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

    RAISE NOTICE 'Input: 15 main questions / 46 follow-ups. Inserted: %, skipped: %, tags created: %, tag relations inserted: %.',
        imported_count, skipped_count, created_tag_count, tag_relation_count;
END;
$agent_interview2_import$;

COMMIT;
