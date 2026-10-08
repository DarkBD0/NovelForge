# NovelForge

> 🚧 **开发中 / 尚未完成**  
> NovelForge 已能在本机跑通小说创建、大纲、人物设定、滚动章节规划、逐章写作、检查、修订、确认和完结流程，但仍是实验性项目，不保证长篇质量、所有题材表现或模型输出始终稳定。

NovelForge 是一个面向中文长篇小说创作的本机多 Agent 工作台。项目使用 Java 21、Spring Boot 和原生 HTML/CSS/JavaScript，一个 Java 服务同时提供网页、API、后台任务与持久化存储。系统已经覆盖结构化大纲、创作对话、滚动章节规划、逐章写作、专业检查、受控修订、独立状态提取和分层记忆。正式使用可将项目、内容版本、任务、对话和审计记录拆分保存到独立 MySQL 数据库；H2 仍用于零配置演示、测试和旧数据迁移。

项目坚持以下边界：

- 模型只能生成候选内容，不能替作者确认版本。
- 大纲、人物设定、章节规划和正文都保留历史版本。
- 检查或模型调用失败时，已经保存的候选不能丢失。
- 正式小说事实只来自作者已经确认的人物设定和正文。
- 当前服务只监听 `127.0.0.1`。

## 核心功能

### 从标题和简介开始创作

用户最少只需提供书名和简介，也可以补充题材、创作要求、目标字数和字数上限。系统按照“大纲 → 人物设定 → 章节规划 → 逐章正文 → 完结检查”的顺序推进，不要求用户提前准备完整设定文档。

### 流式创作对话与操作提案

大纲阶段提供类似智能助手的右侧创作对话。用户可以先讨论书名、简介、世界规则、人物方向、主线和结局，也可以直接要求 Agent 开始生成。消息支持流式显示、Enter 发送、Shift + Enter 换行、停止生成、失败重试和历史记录滚动查看。

每个对话拥有独立记忆，可以新建和切换，不会把另一段讨论自动带入当前会话。Agent 在聊天中提出的书名、简介、补充要求和目标字数修改会先形成字段级提案，展示修改前后内容；只有作者逐项接受后才会写入项目。生成大纲同样先形成可审计的操作提案，再进入正式大纲流水线。聊天回复本身不能直接修改作品、确认版本或跳过工作流。

### 结构化大纲 + 受控多 Agent

大纲阶段由多个专业角色接力完成：人物与世界参谋提供基础材料，故事架构主笔统一编写完整大纲，连贯性检查和情节伏笔检查负责发现问题。Java 工作流控制调用顺序、结构校验和最大修订次数，Agent 不能自行确认大纲或无限循环修改。

大纲以结构化数据保存，覆盖故事核心、世界规则、人物弧、篇章、阶段、关键事件、伏笔、支线和明确结局。作者可以在网页中直接修改这些结构，保存为新候选版本，再只复查受影响的部分。

不同角色不会直接共用一份无限扩张的提示上下文。系统按角色白名单编译模型输入：正文写作可以读取必要的近期章节和已复核历史召回，文风角色只读取当前候选及必要约束，检查角色读取对应证据；未确认内容、未来章节和角色无关字段会被排除。每次 Agent 运行都会保存实际输入、来源版本、策略版本和内容哈希，便于重启恢复与结果回放。

### 滚动章节规划

系统不会一次性把所有章节写死。每批规划根据接下来的剧情密度决定覆盖范围，并在当前批次快结束时提前生成下一批规划。新规划会参考已确认大纲、前面剧情和最近章节，使后续内容能够承上启下。

章节规划决定剧情方向和关键事件，但不会把未写入规划的正常场景细节直接判定为冲突。

### 逐章写作、检查与修订

正文逐章生成、逐章检查和逐章确认。检查意见使用“修改位置 / 问题 / 依据 / 建议”结构，并分为“必须修正 / 作者决定 / 建议优化”三个级别。

作者可以手动修改，也可以把检查意见填入修改要求，让 Agent 生成新版本。局部修订采用受控变更，不允许因为修复一个问题而静默改写整章或改变无关事实。

检查结果绑定候选内容、来源版本和检查策略指纹。相同内容在依据未变化时可以稳定复用已有正式检查，避免重复点击后产生互相矛盾的新意见；正文发生变化时旧报告会立即失效。系统只自动处理“必须修正”项，并限制自动修订轮数，作者判断项会保留给用户查看。

### 作者确认与版本追溯

模型只能提交候选，不能代替作者确认。整份大纲、整份人物设定、每批章节规划和每章正文都需要作者确认后，才能成为后续创作的正式依据。

每次生成、手动编辑和 Agent 修订都会保留版本。修改已确认内容时，系统会根据版本来源标记受到影响的后续规划或正文，避免新旧设定混用。

### 文风检查与安全优化

章节正文可以单独检查赘述、重复解释、无效环境描写和生硬表达。自动文风处理最多执行一轮，并且只允许安全删除能够精确定位的赘句，不得改变人物行为、剧情结果、时间顺序、伏笔、摘要或档案事实。

### 草稿保护与任务恢复

只要模型已经返回可保存的正文，候选就会先落库，再执行检查。摘要缺失、检查失败或模型调用中断不会覆盖原有内容。用户可以只补充摘要、只重新检查或继续编辑当前草稿，不必被迫重新生成正文。

服务重启后，待确认、待完善、状态提取失败和检查失败的候选仍然保留；历史失败任务与诊断记录也不会被新任务覆盖。正文成功但摘要或状态提取缺失时，可以只补充对应结果，不会重新生成或覆盖正文。

专业检查支持从历史样本中按数量回放。回放同时记录旧基线上下文与角色隔离上下文的长度、哈希和策略版本，可用于小样本 A/B；回放只产生评测与审计记录，不修改小说正文或确认状态。

### 独立状态提取与正式记忆

正文写作 Agent 只负责正文和摘要，不再同时编写小说档案。内容检查通过后，独立状态提取 Agent 从候选正文中整理原子事实、实体和有向关系；每条结果必须绑定正文中的逐字证据、来源版本和章节号，无法在正文中定位的内容会被拒绝。局部手动修改正文后，系统会针对新正文重新提取状态，不要求用户同步手工维护内部档案。

人物设定和正文只有在作者确认后，才会形成正式记忆。系统分别保存事实历史、当前事件、时间线、伏笔、实体、别名和带有效章节范围的关系状态；生成后续章节时按角色权限和章节边界读取，不会把未确认候选、聊天内容或未来规划误当成已经发生的剧情。状态提取失败只会阻止当前候选确认，正文、摘要和检查报告仍然保留并可单独重试。正式记忆与内容确认在同一数据库事务中更新，写入失败时不会出现“正文已确认但档案缺失”的半成功状态。

### 可恢复的 Neo4j 关系与依赖投影

启用 Neo4j 后，正式数据会通过 MySQL Outbox 异步投影为小说、内容单元、全部内容版本、来源依赖、事实、事件、伏笔、实体和有向关系。重复任务会被合并，失败任务可以重试，服务重启会恢复中断任务；Neo4j 暂时不可用也不会阻塞正文确认或损坏 MySQL 正式数据。

当前只投影有明确来源的数据，不会根据自然语言猜测人物关系。项目已提供实体关系、有效关系、两跳路径和版本影响范围的影子对照；这些查询用于验证图投影是否与 MySQL 正式库一致，尚不替代正式影响分析。

### Elasticsearch 纯文本影子召回

启用 Elasticsearch 后，系统只索引当前有效的已确认人物设定和章节正文，同时保存小说、章节、档案及来源版本标识。即使已确认版本之后又产生未确认修订稿，正式已确认版本仍会留在索引中。影子查询使用中文 CJK 双字字段与精确标题加权，支持小说隔离和“只检索指定章节之前内容”的边界，并把查询、命中来源、顺序和耗时记录到 MySQL，便于建立固定评测集。通过固定集验收后，可以单独开启正文检索上下文：结果进入提示词前仍会与 MySQL 当前已确认版本复核，过期、未确认和未来章节命中都会被丢弃；Elasticsearch 不可用时自动回退，不阻塞写作。当前尚未启用向量化。

### 历史连续性灰度检查

对于已经积累较多章节的小说，可以按小说白名单开启历史连续性灰度。系统从近期正文窗口之外确定性选择少量已确认章节，使用独立状态提取重建只读历史记忆，再交给连续性检查角色验证远距离事实和关系。模型结论必须引用候选正文和历史正文中的逐字证据，否则不会计入有效问题。

该能力默认关闭，只生成独立灰度报告，不覆盖正式检查、不自动修改正文，也不影响作者确认。历史重建或额外模型调用失败时会自动回退到原有流程。目前它仍处于扩大真实样本验收阶段，不应直接对全部小说启用。

### 本机 Web 工作台与持久化

书籍管理、内容阅读、版本切换、结构化编辑、流式创作对话、任务进度、小说档案和字数设置都在浏览器中完成。前端由同一个 Java 服务提供，不需要单独启动前端工程。

小说、版本、任务、对话和档案可以拆分保存在 NovelForge 独立的 MySQL 数据库中。Elasticsearch 与 Neo4j 只作为可重建的检索、关系投影，不保存唯一正文或唯一确认记录。服务默认只监听 `127.0.0.1`，模型密钥和数据库密码保存在被 Git 忽略的本机配置文件中。

### 兼容模型接口

支持 Chat Completions 兼容接口，可配置模型地址、模型 ID、API Key、超时、上下文和输出额度。项目已针对 DeepSeek V4 Flash 的最终内容、推理内容、JSON 输出和 token 参数差异进行适配，并保留不产生模型费用的离线演示模式。

## 当前能力状态

| 能力 | 当前状态 |
| --- | --- |
| 创建小说到确认完结的基础闭环 | 已可运行，仍需更多长篇质量验收 |
| 结构化大纲和受控多 Agent 流水线 | 已实现并完成真实模型小样本验收 |
| 大纲创作对话、独立会话和项目修改提案 | 已实现；尚未扩展到所有创作阶段 |
| 正文检查、有限自动修订和文风安全处理 | 已实现；模型质量仍受供应商和题材影响 |
| 独立状态提取、事实、实体和关系正式记忆 | 已实现，支持失败单独重试和重启恢复 |
| MySQL 拆分存储和增量写入 | 已实现并完成备份恢复验证 |
| Elasticsearch 检索 | 纯文本召回和可选上下文已实现；向量检索未启用 |
| Neo4j 投影 | 关系与版本依赖影子投影已实现；尚未接管正式影响分析 |
| 历史连续性检查 | 单部小说灰度已通过，正在扩大样本，默认关闭 |
| 按角色选择模型、题材专项 Agent、自主工具调用 | 尚未实现 |

## 快速开始

### 环境要求

- Windows 10/11
- JDK 21
- Maven 3.6.3 或更高版本
- Windows PowerShell 5.1 或 PowerShell 7
- Node.js 18 或更高版本（仅运行可选的网页闭环测试时需要）

确认 Java 和 Maven 已加入 `PATH`：

```powershell
java -version
mvn -version
```

### 1. 克隆项目

```powershell
git clone <你的仓库地址>
cd NovelForge
```

### 2. 先运行离线演示

离线演示不调用真实模型，也不会产生模型费用：

```powershell
.\start.ps1 -Mode demo
```

首次启动会下载 Maven 依赖并构建项目。出现 `Started NovelForgeApplication` 后，访问：

<http://127.0.0.1:8080/>

离线演示使用固定的灯塔故事，只用于验证流程，不代表真实写作质量。

### 3. 配置独立 MySQL（推荐）

零配置演示可以继续使用 H2。要使用拆分存储，先准备 MySQL 5.7 或更高版本，然后复制本机配置模板：

```powershell
Copy-Item .\config\database.local.example.env .\config\database.local.env
```

编辑 `config/database.local.env` 中的连接地址、账号和密码。数据库名应只供 NovelForge 使用，默认是 `novelforge_local`。账号具备建库权限时可执行：

```powershell
.\scripts\provision-storage.ps1
```

启动时会自动执行版本化建表脚本。`database.local.env` 已被 Git 忽略，不要把真实密码写入示例文件。

### 4. 配置真实模型

复制公开的配置模板：

```powershell
Copy-Item .\config\model.local.example.env .\config\model.local.env
```

编辑新建的 `config/model.local.env`，至少填写真实 API Key：

```text
NOVELFORGE_MODEL_MODE=http
NOVELFORGE_MODEL_BASE_URL=https://api.deepseek.com
NOVELFORGE_MODEL_API_KEY=replace-with-your-private-key
NOVELFORGE_MODEL_NAME=deepseek-v4-flash
```

然后启动真实模型模式：

```powershell
.\start.ps1 -Mode http
```

`config/model.local.env` 已被 `.gitignore` 排除，不应提交到 Git。`config/model.local.example.env` 只保存字段示例，应该提交。

> 如果密钥曾经被提交、截图或公开，请立即到模型供应商后台撤销并重新生成；仅从 Git 历史中删除文件并不能使旧密钥失效。

### 5. 可选：启用辅助投影

Elasticsearch 和 Neo4j 不是运行创作闭环的必要条件。需要试验辅助检索或关系投影时，复制本机配置模板并填写专用于 NovelForge 的地址、账号和密码：

```powershell
Copy-Item .\config\auxiliary.local.example.env .\config\auxiliary.local.env
.\scripts\provision-auxiliary-storage.ps1
```

只有把 `NOVELFORGE_NEO4J_ENABLED` 或 `NOVELFORGE_ES_ENABLED` 设为 `true` 后，对应后台同步才会运行。健康接口 `http://127.0.0.1:8080/api/health` 会分别显示待处理、失败、成功任务和同步水位数量。Elasticsearch 默认只提供纯文本影子查询和审计记录；完成自己的固定集验收后，可以再单独开启检索上下文。当前没有生成向量。

### 6. 停止与再次启动

在启动服务的终端按 `Ctrl+C` 停止。

已经成功构建后，可以跳过构建快速启动：

```powershell
.\start.ps1 -Mode http -SkipBuild
```

代码更新后应去掉 `-SkipBuild`，重新构建再启动。

## 模型配置

启动脚本默认读取 `config/model.local.env`，也可以指定其他本机配置文件：

```powershell
.\start.ps1 -Mode http -ModelConfig 'D:\private\novelforge-model.env'
```

主要配置项：

| 配置项 | 示例或默认值 | 说明 |
| --- | --- | --- |
| `NOVELFORGE_MODEL_MODE` | `demo` / `http` | 离线演示或真实接口 |
| `NOVELFORGE_MODEL_BASE_URL` | `https://api.deepseek.com` | 基础地址，程序追加 `/chat/completions` |
| `NOVELFORGE_MODEL_API_KEY` | 无 | 私有密钥；本机无鉴权模型可以留空 |
| `NOVELFORGE_MODEL_NAME` | `deepseek-v4-flash` | 供应商实际模型 ID，不是网页展示名称 |
| `NOVELFORGE_MODEL_TIMEOUT` | `180` | 单次模型请求超时秒数 |
| `NOVELFORGE_MAX_OUTPUT_TOKENS` | `12000` | 单次请求输出额度 |
| `NOVELFORGE_MAX_CONTEXT_CHARS` | `160000` | 本地上下文字符安全阈值，不等于 token 数 |
| `NOVELFORGE_TOKEN_LIMIT_FIELD` | `auto` | 自动选择 token 上限字段，也可显式配置 |
| `NOVELFORGE_RESPONSE_FORMAT` | `none` | 仅在兼容服务明确支持时使用 `json_object` |
| `NOVELFORGE_CONTINUITY_SHADOW_ENABLED` | `false` | 连贯性专业检查实验开关 |
| `NOVELFORGE_PLOT_FORESHADOW_SHADOW_ENABLED` | `false` | 情节伏笔专业检查实验开关 |
| `NOVELFORGE_STRUCTURED_MEMORY_CONTEXT_SHADOW_ENABLED` | `true` | 构建结构化记忆影子上下文，只记录对照，不改变正式 Agent 输入 |
| `NOVELFORGE_STRUCTURED_MEMORY_CONTINUITY_AB_ENABLED` | `false` | 对同一候选执行基础上下文与结构化记忆上下文的连续性 A/B |
| `NOVELFORGE_HISTORICAL_STRUCTURED_MEMORY_SHADOW_ENABLED` | `false` | 从历史已确认章节只读重建结构化记忆；不会写入正式档案 |
| `NOVELFORGE_HISTORICAL_STRUCTURED_MEMORY_SHADOW_MAX_CHAPTERS` | `5` | 单次历史影子重建最多选择的已确认章节数 |
| `NOVELFORGE_HISTORICAL_STRUCTURED_MEMORY_CONTINUITY_AB_ENABLED` | `false` | 历史结构化记忆连续性 A/B 实验开关 |
| `NOVELFORGE_HISTORICAL_CONTINUITY_GRAY_ENABLED` | `false` | 历史结构化记忆连续性灰度检查总开关；还需启用历史影子重建，失败不影响正式检查和确认 |
| `NOVELFORGE_HISTORICAL_CONTINUITY_GRAY_NOVEL_IDS` | 空 | 参与灰度的小说 ID，多个用英文逗号分隔；小范围验证时不要使用 `*` |
| `NOVELFORGE_HISTORICAL_CONTINUITY_GRAY_MAX_CHAPTERS` | `4` | 每次均匀抽取的远期已确认章节上限 |
| `NOVELFORGE_STRUCTURED_MEMORY_MAX_CONTEXT_CHARS` | `6000` | 单次结构化记忆上下文字符额度 |
| `NOVELFORGE_STRUCTURED_MEMORY_MAX_ENTITIES` | `40` | 单次最多选择的相关实体数 |
| `NOVELFORGE_STRUCTURED_MEMORY_MAX_RELATIONS` | `80` | 单次最多选择的相关关系数 |
| `NOVELFORGE_RETRIEVAL_CONTEXT_ENABLED` | `false` | 将验收后的检索结果用于正文生成、内容修订和检查；文风 Agent 不使用 |
| `NOVELFORGE_RETRIEVAL_RESULT_LIMIT` | `5` | 每次最多加入的检索候选数，仍会经过来源版本复核 |
| `NOVELFORGE_RETRIEVAL_MAX_CONTEXT_CHARS` | `6000` | 检索片段可占用的上下文字符上限 |

当前适配器要求模型能够稳定遵循 JSON 结构指令。兼容 Chat Completions 不代表字段、推理模式或 JSON 模式完全兼容，建议先用小目标字数进行测试。

远程模式会把当前任务所需的小说内容发送给配置的模型服务，并可能产生费用。NovelForge 不会在模型失败后自动发起不受控的付费重试；取消本地任务也不能保证供应商停止已经开始的计算或计费。

## 使用流程

1. 新建小说，填写书名、简介以及可选的创作要求和字数；暂时没有书名时也可以先填“未知”。
2. 在右侧创作对话中讨论方向，逐项接受需要的项目修改提案，或直接使用原按钮生成。
3. 生成、检查并确认全书结构化大纲。
4. 生成人物与世界设定，检查后确认。
5. 生成一批章节规划并确认。
6. 逐章生成正文；系统依次执行内容检查、受限文风处理和独立状态提取，再由作者查看、修订和确认。
7. 当前规划快写完时，提前生成下一批规划，使前后批次能够承接。
8. 主线、结局和重要伏笔完成后执行全书完结检查，再由作者确认完结。

模型“检查通过”不等于作者已经确认，确认动作始终保留给用户。

## 数据与备份

未创建数据库本机配置时，零配置演示仍使用：

```text
data/novelforge.mv.db
```

创建 `config/database.local.env` 后，应用使用其中指定的 NovelForge 独立 MySQL 数据库，并以 `normalized` 模式分表保存项目、内容版本、任务、检查、对话、审计记录和已确认的正式记忆。常规更新会依据记录哈希只插入、更新或删除实际发生变化的行，不再为一次局部修改重写整部小说的所有子表；同一次聚合更新仍处在一个数据库事务中，失败会整体回滚。旧 H2 文件只作为迁移来源和回滚备份，不会在普通启动时自动重复导入。

Elasticsearch 与 Neo4j 使用 `config/auxiliary.local.env` 中的 NovelForge 独立命名空间。Neo4j 已支持从 MySQL 正式记忆进行可恢复的影子投影；Elasticsearch 已支持已确认文本的可恢复索引和带来源审计的纯文本影子查询，尚未生成向量。两者都不是唯一事实源，关闭或清空后仍可从 MySQL 重建，不影响完整创作闭环。

`data/`、`backups/`、日志、真实模型配置、数据库本机配置和本机构建产物都已加入 `.gitignore`。

备份前先停止服务。H2 模式复制整个 `data/` 目录；MySQL 模式应备份 `novelforge_local` 数据库，同时保留本机配置。不要通过删除数据库解决启动或迁移问题。

如果 MySQL 运行在本机 Docker 容器 `mysql` 中，可以执行隔离恢复验收：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-mysql-backup.ps1
```

脚本从被 Git 忽略的数据库配置读取连接信息，把备份保存在 `backups/`，恢复到带时间戳的临时 NovelForge 数据库，逐表核对行数和内容校验值，然后只删除该临时验证库。正式数据库只读，不会被恢复操作覆盖。容器名不同时可传入 `-Container`。

## 验证

运行 Java 测试和前端脚本语法检查：

```powershell
.\verify.ps1
```

服务以离线演示模式运行后，可执行完整 HTTP 闭环测试：

```powershell
node .\scripts\smoke.mjs
```

闭环测试会创建一部名称带“【闭环验证】”的演示作品，不会删除已有作品；检测到真实模型模式时会拒绝运行，避免产生费用。

PowerShell 启动脚本可单独验证：

```powershell
powershell.exe -NoProfile -File .\scripts\test-powershell.ps1
```

启用 Elasticsearch 后，可执行已确认章节的标题召回、小说隔离、来源版本和章节边界评测：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\evaluate-shadow-retrieval.ps1
```

要追加本机事实问题集，可以传入被 Git 忽略的 JSON 文件：

```powershell
.\scripts\evaluate-shadow-retrieval.ps1 -CasesPath .\data\retrieval-evaluation.local.json
```

每条事实用 `novelId`、`query`、`beforeChapter` 和 `expectedArtifactIds` 描述。查询会写入 MySQL 检索审计，但不会修改小说内容或确认状态。

当前本机真实基线包含 50 个已确认章节标题和 32 条人工事实问题：标题 Top1/Top5 均为 50/50，事实问题 Top5 为 29/32，小说或来源版本串线为 0，未来章节边界泄漏为 0。3 条未进入前五的问题都属于需要同义或实体语义对应的间接问法，因此纯文本召回暂时达到 90% 验收线，但还不能据此宣称能够稳定替代向量检索。是否增加独立 Embedding 模型，应在更多小说和更长篇幅的固定集上做纯文本与混合召回对照后决定。

启用 Neo4j 后，可以分别核对实体关系投影和版本影响范围投影：

```powershell
.\scripts\evaluate-shadow-relations.ps1
.\scripts\evaluate-shadow-impacts.ps1
```

历史结构化记忆仍属于付费真实模型实验。先准备被 Git 忽略的本地固定集 `runtime/evaluations/historical-continuity-cases.local.json`，并只选择测试小说；启用相关开关后再运行：

```powershell
.\scripts\evaluate-historical-continuity-ab.ps1
```

这些影子和 A/B 脚本只生成评测记录，不应自动确认版本或覆盖正式正文。运行前请检查模型模式、白名单和预计调用费用。

模拟测试通过不等于真实模型、长篇质量或生产环境已经验收。

## 项目结构

```text
NovelForge/
├─ backend/                    Spring Boot 后端、工作流和测试
├─ frontend/                   原生网页界面
├─ config/
│  ├─ model.local.example.env      可公开的模型配置模板
│  ├─ database.local.example.env   可公开的数据库配置模板
│  └─ auxiliary.local.example.env  可公开的辅助投影配置模板
├─ scripts/                    存储初始化、闭环测试和影子评测脚本
├─ start.ps1                  构建并启动服务
└─ verify.ps1                 运行自动化验证
```

运行后生成的 `data/`、`logs/`、`backups/`、`tmp/`、`.m2/` 和 `backend/target/` 不应提交。

## 参与开发

欢迎贡献代码。提 issue 或 PR。

请勿提交 API Key、完整模型响应、私人小说正文或包含个人信息的数据库文件。

## License

1. 未经作者书面授权，严禁将本软件（无论是否修改）用于任何商业目的。
2. 任何个人、企业或组织，如需将本软件用于商业产品、商业服务（SaaS）或任何产生营收的活动，必须提前联系作者获取商业授权。
