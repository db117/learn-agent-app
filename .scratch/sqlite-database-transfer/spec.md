# SQLite 整库导入导出与跨设备手动迁移规格

Triage: ready-for-agent

Status: ready-for-agent

Scope: `learning-agent-java`、Spring Boot WebFlux、SQLite、React/Tauri、本地文件导入导出

Artifact purpose: 将已经确认的 SQLite 整库快照方案整理为可拆票、可实现、可验收的规格。本规格不包含实现。

## Problem Statement

学习者当前的 Learning Journey、LearnUnit、LearningPathItem、Assessment、Attempt、TutorSession、TutorEvent、
AgentState 和设置都只保存在单台设备的本地 SQLite 中。更换设备时，学习者无法把完整学习进度和 Tutor 上下文带到另一台设备；
也没有一个简单方式为数据库制作可恢复备份。

当前 MVP 不需要账号体系、同步服务器或云盘 API。学习者希望直接导出一个完整 SQLite 数据库文件，通过本地文件、OneDrive
同步目录或 Google Drive 同步目录自行传输，再在另一台设备导入。导入按整个数据库覆盖，不做 Journey 级合并；数据库中已有及未来新增的
可移植设置、Agent 配置和密钥也随数据库一起转移。

直接复制正在使用的 SQLite 文件可能得到不一致快照，直接覆盖活动数据库也可能破坏当前数据或留下与内存运行状态不一致的应用。因此导入导出必须由
Spring Boot WebFlux Application 的 SQLite 边界统一完成：导出一致快照，导入前验证、备份和确认，失败时保留原数据库，并在需要时通过既有
Tauri 生命周期重启 JVM 后端。

## Solution

在 Spring Boot WebFlux Application 中增加一个数据库传输能力，通过框架无关的 HTTP 接口导出和导入完整 SQLite 快照。
活动数据库继续保存在本地应用数据目录，不直接放进 OneDrive、Google Drive 或其他实时同步目录。

导出时，后端使用 SQLite/Xerial 已有的一致性备份能力生成一个独立、可直接打开的 `.db` 文件，并通过 HTTP 下载给
React。学习者可以把该文件
保存到任意本地目录，也可以自行放入 OneDrive 或 Google Drive 的本地同步目录。

导入时，React 通过本地文件选择器读取一个 `.db` 文件并提交给后端。后端先把上传内容写入临时文件，验证 SQLite 完整性、外键关系、必需表和精确
`schema.version`，并检查快照时间。如果快照明显旧于当前数据库，先返回警告，只有学习者确认后才继续。应用存在运行中的 TutorAgent
调用或其他不能安全
中断的数据库写入时，导入被拒绝，学习者可在操作结束后重试。

确认导入后，后端先为当前数据库创建带时间戳的完整备份，再把已验证快照作为整个数据库的新内容。替换必须是原子的；任何验证、备份或替换失败都不得改变
当前数据库。导入成功后清除当前页面状态并重新加载数据库；若运行时状态无法在进程内可靠刷新，则由 Tauri 仅负责停止并重新启动
JVM，SQLite 验证和替换
仍由 Java 完成。

SQLite 文件是 MVP 的唯一传输格式和数据边界。未来的 Java、Rust、Python 或其他实现可以围绕同一份框架无关 schema 实现导入导出；
AgentScope 专属 AgentState 会被完整保存，但跨 Agent runtime 解释和恢复不属于本规格。

## User Stories

1. 作为学习者，我希望导出当前完整 SQLite 数据库，从而为所有学习数据创建一个单文件备份。
2. 作为学习者，我希望导出的文件是一个标准 SQLite `.db` 文件，从而可以直接保存、复制和归档。
3. 作为学习者，我希望导出包含所有 Learning Journey，从而不会遗漏其他学习目标的进度。
4. 作为学习者，我希望导出包含每个 Journey 专属的 LearnUnit 和教学内容，从而在另一台设备上继续相同课程。
5. 作为学习者，我希望导出包含所有 LearningPathItem，从而保留当前、完成、跳过和待学习状态。
6. 作为学习者，我希望导出包含所有 Question、Assessment 和固定题集关系，从而保持历史评估可解释。
7. 作为学习者，我希望导出包含全部 Assessment Attempt 和 Question Attempt，从而保留答题、得分、反馈和重试历史。
8. 作为学习者，我希望导出包含 TutorSession、消息和 TutorEvent，从而保留教学对话和可见事件历史。
9. 作为学习者，我希望导出包含 AgentState，从而在兼容的 `learning-agent-java` 运行时中恢复 TutorAgent 上下文。
10. 作为学习者，我希望导出包含 SQLite 中的应用设置和 Agent 配置，从而减少换设备后的重复配置。
11. 作为学习者，我希望未来存入 SQLite 的 OpenAI Key 随数据库一起导出，从而能在导入后恢复该配置。
12. 作为学习者，我希望活动数据库继续留在本地应用目录，从而不会由云盘客户端直接并发修改。
13. 作为学习者，我希望能够把导出的数据库保存到任意本地目录，从而自行选择备份位置。
14. 作为学习者，我希望能够把导出的数据库放入 OneDrive 或 Google Drive 同步目录，从而借助已有云盘客户端传到其他设备。
15. 作为学习者，我希望应用不要求登录 OneDrive 或 Google Drive，从而不增加云盘账号接入步骤。
16. 作为学习者，我希望通过本地文件选择器选择要导入的数据库，从而无需输入文件路径。
17. 作为学习者，我希望导入前看到所选文件的快照时间和 schema 版本，从而知道将要恢复的数据来源。
18. 作为学习者，我希望应用在导入明显较旧的快照前提醒我，从而避免无意中覆盖较新的进度。
19. 作为学习者，我希望确认后仍可导入旧快照，从而能够主动回退到历史状态。
20. 作为学习者，我希望导入时整个 SQLite 数据库被覆盖，从而得到与导出设备一致的完整状态。
21. 作为学习者，我希望整库导入不会尝试合并同名 Journey，从而避免隐式冲突规则产生不可预测结果。
22. 作为学习者，我希望导入前自动备份当前数据库，从而在选错文件后仍有恢复机会。
23. 作为学习者，我希望预导入备份使用带时间戳的名称，从而可以区分多次恢复操作。
24. 作为学习者，我希望导入损坏文件时看到明确错误，从而知道当前数据库没有被替换。
25. 作为学习者，我希望导入非 SQLite 文件时被明确拒绝，从而不会导致应用下次启动失败。
26. 作为学习者，我希望导入不兼容 schema 版本时被明确拒绝，从而不会把旧结构误当成当前数据。
27. 作为学习者，我希望导入失败后继续使用原数据库，从而不会因一次失败操作丢失学习进度。
28. 作为学习者，我希望运行中的 TutorAgent 调用不会被数据库导入悄悄截断，从而保留当前对话的确定结果。
29. 作为学习者，我希望应用忙碌时明确告诉我稍后重试导入，从而理解为什么当前不能覆盖数据库。
30. 作为学习者，我希望导入成功后页面重新加载 Journey 和当前 LearnUnit，从而不会继续展示旧数据库的状态。
31. 作为学习者，我希望兼容运行时在导入后恢复 TutorSession 和 AgentState，从而继续原来的学习对话。
32. 作为学习者，我希望机器专属的数据目录、数据库路径和设备状态不随数据库覆盖，从而避免另一台设备使用无效本地路径。
33. 作为维护者，我希望导出由 SQLite 一致性备份能力完成，从而不需要自行处理 WAL、SHM 或部分写入文件。
34. 作为维护者，我希望导入文件在独立临时位置完成验证，从而不会在验证过程中接触当前活动数据库。
35. 作为维护者，我希望导入验证复用应用启动时的 schema 规则，从而只有一个数据库兼容性定义。
36. 作为维护者，我希望导入导出阻塞工作离开 WebFlux event loop，从而不影响 HTTP/SSE 响应线程。
37. 作为维护者，我希望同一时间只运行一次数据库传输，从而避免多个导入或导出相互覆盖。
38. 作为维护者，我希望 HTTP 接口只暴露文件和项目自有响应结构，从而不泄漏 Spring、JDBC 或 AgentScope 类型。
39. 作为维护者，我希望导入成功后不存在旧数据库连接或旧 Agent 内存状态继续写入新数据库的情况。
40. 作为维护者，我希望 Tauri 只处理必要的 JVM 停止和重启，从而保持 SQLite 业务逻辑在 Java 应用边界内。
41. 作为维护者，我希望 CI 使用临时 SQLite 完成导出、覆盖和恢复测试，从而不接触开发者真实数据。
42. 作为维护者，我希望测试证明导入会删除目标数据库中独有的数据，从而明确验证“整库覆盖”而不是合并。
43. 作为维护者，我希望测试证明预导入备份可以独立打开并恢复，从而验证回退路径可用。
44. 作为维护者，我希望未来其他语言或框架实现可以识别同一个产品级 schema 版本，从而不依赖 Java 或 AgentScope 类名。
45. 作为维护者，我希望不为 MVP 引入账号、同步服务或云盘 SDK，从而保持实现集中在本地 SQLite 文件传输。

## Implementation Decisions

### 产品行为与同步模型

- MVP 使用手动导出、手动导入，不做后台同步。学习者自己通过本地文件、OneDrive 同步目录或 Google Drive 同步目录传输文件。
- 活动数据库始终保存在 `learning-agent-java` 的本地应用数据目录；云盘中只出现导出的稳定快照，不出现活动数据库、WAL 或 SHM
  文件。
- 导入按整个 SQLite 数据库覆盖。目标设备中只存在于当前数据库、而不存在于导入快照的数据会被删除。
- 不做 Journey 级合并、表级合并、事件日志合并、双向同步或冲突自动解决。最后一次由学习者确认并成功导入的数据库成为本地权威状态。
- 旧快照可以覆盖新快照，但必须先显示警告并再次确认。
- 导入成功后必须重新加载完整应用状态；不允许页面继续持有旧 Journey、LearnUnit、Assessment、TutorSession 或 TutorEvent。

### SQLite 快照和数据边界

- 导出格式是单个标准 SQLite `.db` 文件，不增加 ZIP、JSON manifest、增量包或自定义容器格式。
- 导出使用 Xerial/SQLite 已有的在线备份能力生成一致快照；不得通过普通文件复制读取正在使用的数据库。
- 快照包含数据库中的全部表和数据，包括 Learning State、Tutor 数据、TutorEvent、AgentState、设置以及未来写入 SQLite 的 Agent
  配置。
- OpenAI Key 等配置一旦由未来设置功能写入 SQLite，就与其他设置一样随整库快照传输。本规格不增加加密、脱敏、Keychain 或秘密分离。
- 机器专属配置仍由本机运行环境提供，例如数据库路径、数据目录、设备状态和 JVM 启动参数；这些不属于可移植 SQLite 数据。
- AgentScope AgentState 作为不透明数据完整导出和导入。MVP 只保证兼容 `learning-agent-java` 运行时恢复；其他 Agent runtime
  是否能解释该状态不属于当前验收。
- 数据库 schema 是产品级数据协议，不应使用 Java、Spring、Tauri 或 AgentScope 类型作为跨边界接口。新的便携 schema 版本使用框架无关标识。
- 当前框架特定的 schema marker 需要替换为产品级 SQLite schema marker。旧 marker 不迁移；导入时只接受与当前应用完全一致的
  marker。
- 快照元数据存放在 SQLite 文件内部，至少包含 schema 版本、快照创建时间和来源应用标识，不依赖同目录 sidecar 文件。

### 导出接口

- Spring Boot WebFlux Application 提供 `GET /api/database/export`。
- 成功响应使用 SQLite 二进制内容类型并设置下载文件名；默认文件名包含 `learning-agent-java` 和 UTC 时间戳。
- 导出开始前检查数据库传输状态；同一时间已有导入或导出时返回冲突响应。
- 运行中的 TutorAgent 可以继续完成，但导出只保证调用开始时 SQLite 提供的一致快照。若实现无法证明该能力，则返回忙碌响应，而不是复制活动文件。
- 导出失败不得留下被 UI 当作成功备份的半文件。
- 导出中的阻塞 JDBC 和文件操作必须在 WebFlux event loop 之外执行。

### 导入接口与确认

- Spring Boot WebFlux Application 提供 `POST /api/database/import`，接收流式上传的 SQLite 文件，不把完整数据库一次性加载到内存。
- 上传先写入当前数据目录下的唯一临时文件。临时文件不得覆盖活动数据库、既有备份或另一个导入任务。
- 后端验证 SQLite 文件头、`PRAGMA integrity_check`、外键完整性、必需表和精确 `schema.version`。验证规则与应用启动验证共享同一来源。
- 非 SQLite、损坏、缺表、外键损坏、未知 schema 或其他版本的数据库返回不可处理响应，当前数据库保持不变。
- 若快照时间明显早于当前数据库，首次导入返回冲突响应和可展示的比较信息；React 获得学习者确认后以显式覆盖参数重试。
- 同一时间只允许一个数据库传输。存在运行中的 TutorAgent 调用时导入返回忙碌响应，不自动取消 Agent 调用。
- 导入在验证成功后先为当前数据库创建一致的、带时间戳的预导入备份。备份保存在本地应用数据目录的专用备份位置，不自动删除。
- 导入使用经过验证的临时数据库整体替换当前数据库。替换必须发生在没有活动写入的维护窗口中，并使用同文件系统内的原子操作或
  SQLite 提供的等价原子恢复能力。
- 若替换失败，应用恢复或继续使用导入前数据库，保留预导入备份，并返回明确错误。
- 成功导入后删除临时上传文件，保留预导入备份，并返回项目自有结果，包含导入时间、schema 版本和是否需要重启。
- 若 AgentScope、EventHub 或其他内存状态不能在进程内可靠清空，则导入完成后通过既有 Tauri 生命周期重启 JVM。Rust
  只负责进程生命周期，不读取、验证或修改 SQLite。
- 非 Tauri 开发运行方式在需要重启时返回明确提示，由开发者手工重启后端。

### React/Tauri 交互

- React 提供“导出数据库”和“导入数据库”入口，并展示进行中、成功、失败、忙碌、旧快照和版本不兼容状态。
- 导入使用平台文件选择能力选择单个 `.db` 文件；扩展名只用于选择器提示，真正有效性由后端验证。
- 导出使用浏览器/WebView 下载能力；学习者自行选择或移动文件到本地、OneDrive 或 Google Drive 目录。
- 导入旧快照时必须有明确二次确认；正常导入不增加额外确认步骤。
- 导入成功后 React 清空旧页面状态，必要时调用既有 Tauri 后端停止/启动能力，然后从 HTTP API 重新加载 Journey 列表。
- Tauri 不新增 SQLite 表、查询、schema 校验、文件合并或云盘逻辑。

### 设置与未来配置

- 当前已有的 `setting` 数据随数据库完整导入导出，不为不同 key 建立单独同步规则。
- 未来 Agent 配置和应用设置只要持久化到 SQLite，就自动进入快照范围，不需要修改数据库传输协议。
- 本规格不实现尚不存在的设置页面、OpenAI Key 编辑功能或运行中 provider 重建；这些功能进入独立规格后，应使用 SQLite
  作为可移植持久化边界。
- 本规格不增加任何加密、认证、授权、密钥隐藏或敏感字段过滤机制。

### 兼容性和未来实现

- MVP 只接受精确匹配当前 schema 版本的快照，不执行旧 schema 迁移、向前迁移、向后迁移或字段补齐。
- Java、Rust、Python 等未来实现可以复用同一 SQLite schema 和快照协议，但必须各自实现相同的完整性与版本校验。
- 框架无关的 Learning State、Tutor 消息和设置属于可移植数据；框架专属 AgentState 只保证字节级保留，不承诺跨 runtime 恢复。
- 云盘 API 接入未来可以复用导出文件和导入接口，不改变当前数据库数据模型。

## Testing Decisions

### 主测试 seam

- 主 seam 是固定 loopback 上的 Spring Boot WebFlux 数据库传输 HTTP API，测试使用真实临时 SQLite 文件，而不是 mock
  Repository、mock
  文件系统或内存数据库。
- 通过 `WebTestClient` 调用导出和导入接口，在数据库中预先写入代表性
  Journey、LearnUnit、LearningPathItem、Assessment、Attempt、
  TutorSession、消息、TutorEvent、AgentState 和 setting；导出后改变目标库，再导入并从公开查询接口验证完整恢复。
- 这个 seam 同时验证 HTTP 合约、WebFlux 阻塞隔离、SQLite 一致快照、schema 校验、整库覆盖和应用状态重载，是本功能的主要行为证据。

### 必须覆盖的行为

- 导出的文件可由 SQLite 打开，`integrity_check` 成功，并包含所有代表性数据类别。
- 导出快照与活动数据库独立；导出后的本地写入不会改变已导出的文件。
- 整库导入后，快照中的数据全部存在，目标数据库独有的数据全部消失。
- setting 和 AgentState 随整库导入导出，不被单独过滤。
- 导入前会生成一个可独立打开、完整性检查通过的当前数据库备份。
- 非 SQLite 文件、截断文件、损坏数据库、缺失 schema marker、缺失必需表和错误 schema 版本全部被拒绝。
- 所有拒绝和失败路径都保留当前数据库内容，不留下半替换数据库。
- 旧快照第一次导入返回警告；显式确认后可以成功覆盖。
- 并发导入/导出被拒绝或串行化，不产生相互覆盖。
- 运行中的 TutorAgent 调用阻止整库导入，调用结束后可以重试。
- 导入成功后公开 Journey、Path、Assessment 和 TutorSession 查询只返回新数据库内容。
- 导入成功后兼容 AgentState 可以恢复；旧内存状态不能继续写入已替换数据库。
- JDBC、SQLite backup/restore 和文件 I/O 不在 WebFlux event loop 上执行。
- 失败的导入会清理临时上传文件；成功导入保留预导入备份，不残留上传临时文件。

### 桌面和构建验证

- 在 HTTP 主 seam 之外，只增加一条桌面 smoke：React 发起导出和导入，Tauri 在需要时重启 JVM，页面随后恢复快照中的 Journey。
- React 测试只验证文件选择、旧快照确认、状态展示和成功后重载，不重复测试 SQLite 数据语义。
- Tauri 测试只验证既有 JVM 生命周期能够完成导入后的重启，不在 Rust 中重复数据库测试。
- 相关前端、Tauri 和后端修改完成后运行 `pnpm check`；Native Image 不属于本规格验收。

### 既有测试先例

- 数据库初始化与 schema 拒绝行为沿用现有 DatabaseInitializer 集成测试风格。
- 完整学习数据写入与恢复沿用现有 Spring Boot + JdbcClient 集成测试风格。
- HTTP 状态、WebFlux 调度和固定端口行为沿用现有 WebTestClient E2E 风格。
- JVM 启停和临时数据目录沿用现有桌面 smoke 脚本风格。

## Out of Scope

- OneDrive API、Google Drive API、Dropbox API、WebDAV 或其他云盘 SDK 接入。
- 云盘账号登录、OAuth、账号体系、设备绑定和远端用户身份。
- 后台目录监控、自动上传、自动下载、自动覆盖或定时同步。
- 多设备同时写入、Journey 级合并、表级合并、冲突自动解决和操作日志同步。
- 离线写入队列、增量同步、双向同步、服务端权威数据库和同步服务器。
- 数据库、OpenAI Key、Agent 配置或快照的加密、脱敏、Keychain、权限保护和密钥轮换。
- 旧 schema 数据迁移、跨版本导入、最佳努力兼容和未知表恢复。
- ZIP、JSON、CSV 或自定义归档格式。
- 自动删除、数量限制、保留周期和磁盘配额管理；预导入备份由学习者自行管理。
- Journey 单独导出、Journey 单独导入和选择性恢复。
- AgentState 在 AgentScope 之外的 runtime 中恢复或转换。
- 新增设置页面、OpenAI Key 编辑、模型切换和 provider 热重载。
- Android、iOS、浏览器云端版本和 Native Image 数据传输适配。

## Further Notes

- 本规格是 AgentScope 迁移完成后的独立功能，不改变迁移规格本身的历史验收结论。迁移规格中“跨设备同步”和“密钥持久化”曾被列为当时范围外；本规格仅将
  SQLite 手动传输和未来 SQLite 配置纳入新的后续范围。
- “同步”在本规格中只表示学习者手工传输完整数据库快照，不表示实时或自动多设备同步。
- `Learning Journey`、`LearnUnit`、`LearningPathItem`、`TutorSession`、`TutorEvent` 和 `AgentState` 继续使用 `CONTEXT.md`
  中的既有语义；导入导出不能改变任何学习状态权限或让 TutorAgent 决定 Learning Engine 结果。
- SQLite schema 是跨实现的数据协议；HTTP DTO 仍是 React/Tauri 的运行时接口，两者不应合并成一个框架类型。
- 当前 `learning-agent-java` 正式数据库默认位于本地用户数据目录；开发环境仍可通过配置使用项目内隔离数据库。
