# NovelForge

> 🚧 **开发中 / 尚未完成**  
> NovelForge 已能在本机跑通小说创建、大纲、人物设定、滚动章节规划、逐章写作、检查、修订、确认和完结流程，但仍是个人开发阶段的实验性项目。它不应被视为成熟的自动写作产品，也不保证长篇质量、所有题材表现或模型输出始终稳定。

NovelForge 是一个面向中文长篇小说创作的本机 Agent 工作台。项目使用 Java 21、Spring Boot、原生 HTML/CSS/JavaScript 和 H2 文件数据库，一个 Java 服务同时提供网页、API、后台任务与持久化存储。

项目坚持以下边界：

- 模型只能生成候选内容，不能替作者确认版本。
- 大纲、人物设定、章节规划和正文都保留历史版本。
- 检查或模型调用失败时，已经保存的候选不能丢失。
- 正式小说事实只来自作者已经确认的人物设定和正文。
- 当前服务只监听 `127.0.0.1`。

## 核心功能

### 从标题和简介开始创作

用户最少只需提供书名和简介，也可以补充题材、创作要求、目标字数和字数上限。系统按照“大纲 → 人物设定 → 章节规划 → 逐章正文 → 完结检查”的顺序推进，不要求用户提前准备完整设定文档。

### 结构化大纲 + 受控多 Agent

大纲阶段由多个专业角色接力完成：人物与世界参谋提供基础材料，故事架构主笔统一编写完整大纲，连贯性检查和情节伏笔检查负责发现问题。Java 工作流控制调用顺序、结构校验和最大修订次数，Agent 不能自行确认大纲或无限循环修改。

大纲以结构化数据保存，覆盖故事核心、世界规则、人物弧、篇章、阶段、关键事件、伏笔、支线和明确结局。作者可以在网页中直接修改这些结构，保存为新候选版本，再只复查受影响的部分。

### 滚动章节规划

系统不会一次性把所有章节写死。每批规划根据接下来的剧情密度决定覆盖范围，并在当前批次快结束时提前生成下一批规划。新规划会参考已确认大纲、前面剧情和最近章节，使后续内容能够承上启下。

章节规划决定剧情方向和关键事件，但不会把未写入规划的正常场景细节直接判定为冲突。

### 逐章写作、检查与修订

正文逐章生成、逐章检查和逐章确认。检查意见使用“修改位置 / 问题 / 依据 / 建议”结构，并分为“必须修正 / 作者决定 / 建议优化”三个级别。

作者可以手动修改，也可以把检查意见填入修改要求，让 Agent 生成新版本。局部修订采用受控变更，不允许因为修复一个问题而静默改写整章或改变无关事实。

### 作者确认与版本追溯

模型只能提交候选，不能代替作者确认。整份大纲、整份人物设定、每批章节规划和每章正文都需要作者确认后，才能成为后续创作的正式依据。

每次生成、手动编辑和 Agent 修订都会保留版本。修改已确认内容时，系统会根据版本来源标记受到影响的后续规划或正文，避免新旧设定混用。

### 文风检查与安全优化

章节正文可以单独检查赘述、重复解释、无效环境描写和生硬表达。自动文风处理最多执行一轮，并且只允许安全删除能够精确定位的赘句，不得改变人物行为、剧情结果、时间顺序、伏笔、摘要或档案事实。

### 草稿保护与任务恢复

只要模型已经返回可保存的正文，候选就会先落库，再执行检查。摘要缺失、检查失败或模型调用中断不会覆盖原有内容。用户可以只补充摘要、只重新检查或继续编辑当前草稿，不必被迫重新生成正文。

服务重启后，待确认、待完善和检查失败的候选仍然保留；历史失败任务与诊断记录也不会被新任务覆盖。

### 本机 Web 工作台与持久化

书籍管理、内容阅读、版本切换、结构化编辑、任务进度、小说档案和字数设置都在浏览器中完成。前端由同一个 Java 服务提供，不需要单独启动前端工程。

小说、版本、任务和档案保存在本机 H2 文件数据库中。服务默认只监听 `127.0.0.1`，模型密钥保存在被 Git 忽略的本机配置文件中。

### 兼容模型接口

支持 Chat Completions 兼容接口，可配置模型地址、模型 ID、API Key、超时、上下文和输出额度。项目已针对 DeepSeek V4 Flash 的最终内容、推理内容、JSON 输出和 token 参数差异进行适配，并保留不产生模型费用的离线演示模式。

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

### 3. 配置真实模型

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

### 4. 停止与再次启动

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

当前适配器要求模型能够稳定遵循 JSON 结构指令。兼容 Chat Completions 不代表字段、推理模式或 JSON 模式完全兼容，建议先用小目标字数进行测试。

远程模式会把当前任务所需的小说内容发送给配置的模型服务，并可能产生费用。NovelForge 不会在模型失败后自动发起不受控的付费重试；取消本地任务也不能保证供应商停止已经开始的计算或计费。

## 使用流程

1. 新建小说，填写书名、简介以及可选的创作要求和字数。
2. 生成并确认全书大纲。
3. 生成人物与世界设定，检查后确认。
4. 生成一批章节规划并确认。
5. 逐章生成正文；每章检查、修订并由作者确认。
6. 当前规划快写完时，提前生成下一批规划，使前后批次能够承接。
7. 主线、结局和重要伏笔完成后执行全书完结检查，再由作者确认完结。

模型“检查通过”不等于作者已经确认，确认动作始终保留给用户。

## 数据与备份

默认数据库位于：

```text
data/novelforge.mv.db
```

`data/`、`backups/`、日志、真实模型配置和本机构建产物都已加入 `.gitignore`。

备份前先停止服务，然后复制整个 `data/` 目录。不要同时启动两个实例访问同一个数据库，也不要通过删除数据库解决文件锁问题。出现数据库占用时，应关闭旧 NovelForge 进程或数据库查看工具。

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

模拟测试通过不等于真实模型、长篇质量或生产环境已经验收。

## 项目结构

```text
NovelForge/
├─ backend/                    Spring Boot 后端、工作流和测试
├─ frontend/                   原生网页界面
├─ config/
│  └─ model.local.example.env  可公开的模型配置模板
├─ scripts/                    启动检查和闭环测试
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
