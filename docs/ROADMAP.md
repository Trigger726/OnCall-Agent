# OpsPilot 高价值迭代路线

路线按“真实业务价值、面试可讲性、可验证性”排序。每一项完成前不在简历中宣称。

## 已完成：V1.1 可解释 Agent 调查

- `PLAN -> EXECUTE -> REPLAN -> FINISH` 调查编排。
- 告警、CMDB、变更、Runbook 四个只读工具适配器。
- run/step 持久化，记录状态、输入、证据、错误和耗时。
- Incident 与 OnCall 共享执行轨迹，审计和时间线引用 `agent-run:{id}`。
- H2/MySQL 兼容 Flyway V3，JUnit/MockMvc 覆盖端到端回读。

## 已完成：V1.2 可观测证据 Provider

- 定义 Metrics/Logs Provider SPI 与优先级路由，保留可复现的本地演示 Provider。
- 接入 Prometheus instant query API，保存查询表达式、时间范围、实际 Provider、warning 和外部引用。
- 外部指标加入连接/读取超时、重试、跨请求熔断与本地降级。
- 日志证据在进入 Agent 前脱敏密码、Token、Authorization、邮箱和完整 IPv4。
- Agent 扩展为六个只读工具、9 步轨迹；Flyway V4、集成测试与可靠性单测覆盖完整链路。

简历价值：可讲适配器模式、外部系统可靠性、证据溯源和敏感日志治理。

## 已完成：V1.3 外部日志平台与契约测试

- 实现 Loki Logs Provider，支持 LogQL 模板、纳秒时间窗、limit/direction、租户头和 Bearer Token。
- 解析 stream labels 与常见 JSON 日志字段，提取 level、logger、traceId 并在证据形成前脱敏。
- 用真实本机 HTTP 请求验证 Prometheus/Loki 请求参数、请求头和响应映射，修复花括号被 URI 模板误判的问题。
- 覆盖 Loki 失败后本地日志降级，以及通用重试、熔断、半开恢复。

## 已完成：V1.4 可恢复事件流与受控处置

- 调查事件先持久化，再实时发送 `RUN_STARTED / STEP_STARTED / EVIDENCE_COLLECTED / STEP_FAILED / ACTION_PROPOSED / RUN_COMPLETED` 等 SSE 事件。
- 数据库事件 ID 作为重连游标，支持按 `after` 回放缺失事件；客户端断开不取消后台调查。
- 使用有界线程池执行异步调查，并在进入线程前捕获用户与请求 IP，保证资源上限和审计身份。
- 对高置信度变更关联生成回滚草案，必须经 `ADMIN/OPS_MANAGER` 独立审批；禁止申请人自批，版本冲突返回 409。
- 创建和审批进入 Incident 时间线与审计日志；批准只解除治理门禁，不接触生产环境。
- Incident 工作台与 OnCall 助手复用同一真实 Agent 事件流，桌面和移动端完成交互验收。

简历价值：可讲异步事件、断线恢复、人机协同和高风险操作控制。

## 已完成：V1.5 运行控制与交付质量

- 已完成：同一 Incident 的调查幂等键、重复 run 抑制和终态后的客户端键轮换。
- 已完成：排队状态、显式取消、全链路超时预算、队列饱和终态和统一事件序号。
- 已完成：取消竞态、极短超时、队列拒绝和执行器中断自动化测试；Incident/OnCall 桌面与移动端实页验收。
- 已完成：Testcontainers 在真实 MySQL 8.4 上验证 Flyway V1-V6、幂等约束和完整调查链路，并补齐 `flyway-mysql` 正式运行依赖。
- 已完成：SSE emitter 断开隔离、单 worker 多请求队列饱和、排队取消、审批版本冲突和 Provider 可靠性自动化覆盖。
- 已完成：GitHub Actions 拆分前端、H2/JAR、MySQL 和容器四个门禁；镜像以非 root 用户启动并通过健康检查。

简历价值：把“功能能跑”推进到可重复交付、可控并发和真实数据库兼容。

## 进行中：V1.6 Runbook 知识库与检索评测

- 检查点 01 已完成：Markdown/PDF 入库、标题感知分块、内容哈希幂等、不可变版本和角色 ACL。
- 检查点 01 已完成：本地 BM25 中文/英文词元检索、分数排序和版本化 chunk 引用；Agent 的 Runbook 工具复用同一检索服务。
- 检查点 01 已完成：固定改写查询集同时记录原版关键词 contains 基线与 BM25 的 Recall@3/MRR，并记录新版引用命中率和失败样例。
- 检查点 01 已完成：后端自动化、前端生产构建及桌面/移动端实页验收；真实 MySQL V7 复验受本机 Docker Desktop 启动故障阻塞。
- 检查点 02 已完成：Flyway V8 持久化版本绑定的 chunk 向量和索引运行；外部 Embedding 在事务外生成，校验后原子替换，内容指纹相同则幂等复用，失败不破坏旧索引。
- 检查点 02 已完成：BM25 与余弦向量名次使用 RRF 融合；`AUTO / BM25 / HYBRID` 返回实际引擎、双路名次、覆盖率和降级 warning，残缺索引或 Provider 故障确定性回退 BM25。
- 检查点 02 已完成：固定种子集从 3 条扩充到 13 条，按 contains、BM25、Hybrid 分别持久化指标和 unavailable 原因；默认演示 BM25 Recall@3 100%、MRR 96.15%、首位引用 92.31%，不再维持人为全满分。
- 检查点 02 已完成：受控 Embedding 测试替身验证完整索引、RRF、幂等重建、旧索引保护与覆盖不足降级；桌面/移动端实页展示原版/BM25/Hybrid 对照。真实 DashScope 质量和 MySQL V8 尚无本轮直接证据。
- 检查点 03 已完成：Flyway V9 保存控制台/Agent 真实查询、引擎状态、耗时和结果快照，离线评测不写查询日志；查询本人只能对实际返回文档提交 0–3 级判断。
- 检查点 03 已完成：管理员/运维经理待办排除本人判断，禁止自审并用版本号防并发覆盖；批准且等级 ≥ 2 的正判断晋级 `HUMAN_JUDGMENT` 固定 case，负判断只保留分析事实。
- 检查点 03 已完成：专用集成测试覆盖归属/快照校验、角色 403、自审、版本冲突、正负分流和评测流量隔离；全量 30 项测试、前端构建、最新 JAR 及桌面/移动端双账号实页闭环通过。
- 检查点 04 已完成：Flyway V10 保留 1–3 级正相关等级；相同查询的多个相关文档聚合成分级 qrels，同一 query-document 的重复复核等级取平均，评测不再把一个查询重复计权。
- 检查点 04 已完成：按唯一查询计算 Recall@3、MRR、NDCG@3 和首位稳定引用命中率，并分别持久化查询数与 qrels 数；13 条种子集的 BM25 NDCG@3 为 97.16%。
- 检查点 04 已完成：多相关文档集成测试、全量 30 项测试、前端生产构建和最新 JAR 启动通过。当时待验的真实 MySQL 已在检查点 06 以 V1–V12 空库迁移补齐。
- 检查点 05 已完成：Flyway V11 保存独立复核评分；待办接口隐藏原始等级、评论和提交人，只提供查询与不可变结果快照，减少锚定偏差。
- 检查点 05 已完成：复核评分作为最终 qrel 等级，拒绝样本与历史单评分记录不进入一致性统计；页面展示精确一致率和线性加权 Cohen's kappa，并正确处理空集/无类别变化时 κ 未定义。
- 检查点 05 已完成：公式单元测试与 API 集成测试通过，全量增至 33 项；前端生产构建和 V11 JAR/API 烟测通过。当时待验的真实 MySQL 已在检查点 06 以 V1–V12 空库迁移补齐。
- 检查点 06 已完成：Flyway V12 为检索快照增加 `ACTIVE/PURGED` 生命周期、脱敏字段计数和清理时间；查询、结果自由文本、评分评论与复核备注在入库前屏蔽密码、Token、Authorization、邮箱和完整 IPv4。
- 检查点 06 已完成：默认 30 天、每日批量清理到期 payload；待复核样本自动拒绝，查询正文/哈希/结果/查询人不可逆擦除，每个有效批次写审计，重复执行幂等，已晋级 qrel 保持可评测。
- 检查点 06 已完成：管理接口与页面展示保留期、活跃/到期/已擦除数量和累计脱敏字段，可手动触发受权清理；含真实敏感测试串的端到端用例覆盖权限、脱敏、过期、审计、幂等和 qrel 保留。
- 待完成：从真实但脱敏的历史 Incident、Runbook 维护记录和查询流量持续积累双评分样本，补查询频率/故障类型分层；将保留策略扩展到备份、导出副本与单条受控删除。样本量扩大或标注人超过两人后再引入第三方仲裁/多标注人指标，并验证真实 Embedding 与 cross-encoder rerank 是否稳定优于 BM25/RRF。

简历价值：用可复现评测替代“RAG 效果很好”的空泛描述。

## 进行中：V1.7 分布式事件与复盘指标

- 检查点 23 已完成：在真实容器门禁中停止 Tempo，验证业务调查与应用健康不受影响，Collector 真实观察到 gRPC `Unavailable/connection refused`，并在 60 秒有界重试窗口内恢复同一 run 的完整 Trace；Run 52 的精简结果已归档。结论仅覆盖短暂下游故障，不扩大为 Collector 重启后零丢失。

- 检查点 22 已完成：可选 OpenTelemetry Collector + Tempo + Grafana Compose profile，以及真实告警、六工具调查、TraceQL 搜索、Grafana 数据源代理读回、父子层级和敏感正文排除门禁；Run 49 暴露的离线 Compose 密钥占位缺陷已修复，Run 50 远端七项 CI 全部通过。

- 检查点 21：接入 Micrometer Tracing + OpenTelemetry/OTLP，用四类受控业务 span 串联告警接入、异步 Agent run、工具步骤和 Metrics/Logs Provider；线程切换继承 HTTP 父 span，无父上下文安全退化。本地 7 项定向、85 项后端全量、13 项前端与真实 OTel SDK exporter 层级验证已通过；远端真实 MySQL、Redis、双 JVM SSE、容器在六项 CI 中全部通过。真实 Collector UI 仍待补齐。

- 检查点 20：新增持久化 deadline 恢复协调器，存活实例对崩溃后留在 `QUEUED / RUNNING` 的 run 以行锁幂等结算，写入终态事件、时间线和审计；编排器阻止晚返回工具覆盖已恢复终态。H2/本地 JAR、真实 MySQL 双协调者、MySQL/Redis 双 JVM 强制退出及远端六项 CI 均通过，原始结果已归档；不声称自动续跑或 exactly-once 执行。

- 检查点 19：OnCall 助手在 390px/820px 增加 Incident 与 Agent 调查抽屉，桌面保留内联栏；严格浏览器回归发现取消后的原 POST SSE 可能停在 STREAMING，Incident/助手均改为中止旧连接并对同一 run GET 回放终态。13 项前端测试、生产构建、Edge 新旧对照及远端六项 CI 通过，证据已归档。

- 检查点 18：Incident 与 OnCall 助手从服务端发现活动 run，刷新后 GET-only 重建轨迹并保留会话 URL；13 项前端测试、真实浏览器新旧对照与六项 CI 通过，证据已归档。移动端 Agent 面板和双实例浏览器切换仍待完善。

- 检查点 17：修复 Redis Stream 重建后回退/复用 ID 导致接收器持续空读，空读尾检查与每轮 100 条有界恢复；8 项单元测试、4 项真实 Redis、2 项双 JVM 回归及六项 CI 通过，原始结果已归档，数据库兜底保持启用。

- 检查点 16：真实 Redis pause 期间双实例各完整收到 20 条事件，恢复后 outbox 从 18 条自动排空；正常/故障双用例及六项 CI 通过。通知重复/乱序不影响数据库有序 SSE；Redis 重启/数据丢失、慢 socket 和断连日志分级仍待验证或完善。

- 检查点 15：双独立 JVM + MySQL/Redis + HTTP SSE 自动化门禁通过，排除首次及定期补读干扰后，两端 20 条事件与数据库完全一致，跨实例游标续订通过；六项 CI 与原始结果已归档。运行中故障注入仍待验。

- 检查点 14：前端 POST → GET 自动续订、游标去重和退出中止，9 项前端故障测试、Incident 新旧桌面/移动端对照及五项 CI 通过；OnCall 独立实页、双实例与刷新后自动挂接待验。

- 检查点 13：新增 GET SSE、连接/发送任务上限、终态关闭与数据库定期补读，63 项默认回归和五项 CI 通过；前端自动恢复与双 Web 实例演示仍待完成。

- 使用 Redis Streams 或 Kafka 分发多实例 Agent 事件，保留数据库作为回放与审计事实源。
- 检查点 12：独立 XREAD 接收器、双接收器广播/重试/重建回读测试及五项 CI 通过；本地订阅、GET SSE、数据库兜底与前端恢复仍待实现。
- 检查点 11：已投递 outbox 有限批次清理、Redis 通知定期裁剪、原始事件回放保留；真实 Redis、MySQL V18 与五项 CI 通过。
- 检查点 10：Redis Streams 发送端已实现独立调度、XADD 标识通知、租约确认和指数退避；真实 Redis 与五项 CI 通过。
- 检查点 09：V16/V17 已实现可选同事务 outbox、条件领取、租约到期重领与 token 保护，H2/MySQL 与四段式 CI 通过；Redis relay、自动退避、跨实例订阅与前端恢复仍待实现。
- 跨实例实现决策见 [ADR-001](ADR-001-distributed-agent-events.md)：同事务 outbox + 独立 XREAD 广播 + 数据库补读 + 前端游标恢复。双 JVM HTTP SSE 正常链路、Redis pause 故障、Incident/OnCall 助手单实例浏览器刷新挂接已验收；执行 JVM 崩溃后的 deadline 终态结算已进入真实双进程 CI，任务自动迁移、完整故障矩阵与双实例浏览器演示仍未验收。
- 检查点 24：Prometheus/Loki 改用 Spring Boot 自动配置的 `RestClient.Builder`，真实本机 HTTP 端点已验证 W3C `traceparent`、HTTP client span 与 `root -> provider.query -> client` 父子关系；本地 86 项回归、JAR 与远端 Run 54 七项 CI 已通过。真实下游 server span、跨服务 Tempo 拼接、生产采样/保留策略和 Collector 重启后持久队列仍待验。
- 检查点 25 已完成：仅验收 profile 启动独立 instrumented Provider fixture，OpsPilot 真实调用 Prometheus/Loki 兼容端点；Run 58 七项 CI 全绿，Tempo 原始 trace 在正常与短暂停机恢复场景均严格验证两条 `provider.query -> CLIENT -> SERVER` 分支、两个 service.name、唯一 traceId、各级 parent ID 与敏感哨兵排除。双 JVM 崩溃恢复测试同时修正 20 秒等待短于 30 秒 outbox 租约的误报，Run 58 验证终态事件与 `pendingOutboxAfterRecovery=0`；Run 57 的失败证据保留。
- 检查点 01 已完成：已恢复/已关闭 Incident 可从当时的时间线、告警、调查报告和变更引用生成脱敏、不可漂移的无责复盘草稿；重复创建幂等。
- 检查点 01 已完成：五类正文完备校验、至少一个有负责人/期限的防复发行动项、提交人禁止自审、退回修改、独立发布和发布后正文冻结；复盘与行动项均使用乐观锁并进入时间线和审计。
- 检查点 01 已完成：H2 端到端、真实 MySQL 8.4 V1–V13、前端生产构建、最新 JAR，以及桌面/390px 移动端真实页面验收通过；历史 Demo 和 V1.6 对照保留。
- 检查点 02 已完成：按 Incident 创建窗口和严重等级统计 MTTA/MTTM/MTTR，分别暴露均值、中位数和有效样本数，排除缺失/负时长，并提供严重等级分布和慢事故下钻。
- 检查点 02 已完成：跨 Incident 行动项运营视图、截止日边界、逾期天数、每日扫描、角色权限、一行动项一条持久化升级事实、重复扫描幂等及完成后关闭；明确不冒充外部渠道送达。
- 检查点 02 已完成：H2 全量回归、真实 MySQL 8.4 V1–V14、前端生产构建、最新 JAR、SPA 深链，以及桌面/390px 宽表真实页面验收。
- 检查点 03 已完成：按“归属服务 + 精确告警指纹”识别跨 Incident 复发，以不同 Incident 为分母，单事故内高 occurrence 只作为噪声规模；接口返回匹配原因、不同日期、时间跨度、未关闭数和信号总量，不伪造相似度概率。
- 检查点 03 已完成：管理角色将候选并发/重复幂等提升为唯一 Problem，固化历史关联，并在新同指纹 Incident 接入时自动关联；`OPEN / KNOWN_ERROR / RESOLVED` 具备字段门禁、乐观锁、时间线和审计，解决后复发明确显示但不静默重开。
- 检查点 03 已完成：Flyway V15、H2 40 项全量回归、真实 MySQL 8.4、前端生产构建、最新 JAR，以及桌面/390px 的登记、状态流转和解决后复发实页验收；同时通过浏览器回归清除了重复 SPA forward 控制器导致的深链 500。
- 检查点 04 已完成：复现并修复合法长服务名/告警标题导致 Problem 登记 500；自动摘要有界且保留完整源证据，41 项回归与 JAR 通过，MySQL 边界断言及远端四段式 CI 已通过。
- 检查点 05 已完成：MySQL 条件套件新增 REPEATABLE READ 双事务确定性闸门，两个事务先建立“记录不存在”快照再同时提升。首次远端运行证明只把主键回读改成 `SELECT ... FOR UPDATE` 仍不足，后续普通读取继续命中旧快照；冲突恢复整体移入 `REQUIRES_NEW` 新事务后，42 项默认回归、真实 MySQL 2/2、前端生产构建及容器健康烟测全部通过。
- 检查点 26 已完成：按 CMDB 服务拆分 Incident 数、未关闭数与各自 MTTA/MTTM/MTTR 有效样本，继续明确事故响应指标不是可用性 SLO。
- 检查点 27 已完成：Flyway V19 持久化服务 SLO 目标、滚动窗口和版本化 PromQL；使用 Prometheus 好事件/总事件计算 SLI 与错误预算，异常/缺失分母拒算，目标修改带权限、乐观锁和审计。本地全量、前端、真实页面与 [Run 35613655790](https://github.com/Trigger726/OnCall-Agent/actions/runs/35613655790) 的 MySQL 8.4/七项门禁已闭环。
- 检查点 28 已完成：按 Google SRE 三档默认参数计算长/短窗口燃烧率，只在两窗口同时超阈值时分级为急速 PAGE、持续 PAGE 或工单；异常分母仍拒算。默认后端 98 项发现/87 项执行、前端 13 项、生产构建、JAR、启用/关闭两种真实页面状态，以及 Run 35741029914 七项远端门禁均通过。
- 检查点 29 已完成：新增默认关闭、专用密钥保护的 Alertmanager v4 批量 webhook；fingerprint + startsAt 形成生命周期幂等键，同状态重放零写入，firing/resolved 更新同一告警并留恢复时间线，永久坏项批内隔离。默认后端 102 项发现/91 项执行、前端 13 项、JAR、真实 HTTP/实页，以及 [Run 35756244777](https://github.com/Trigger726/OnCall-Agent/actions/runs/35756244777) 七项远端门禁均通过。
- 检查点 30 已完成：Flyway V20 持久化脱敏 Alertmanager 拒绝快照；动态 firing `endsAt` 依稳定键归并，重复投递不抢占租约；管理/值班角色可短租约重放，AUDITOR 只读，成功/失败可审计。本地 104 项后端、13 项前端、JAR、真实 HTTP 和响应式实页已通过，[Run 35762227045](https://github.com/Trigger726/OnCall-Agent/actions/runs/35762227045) 的 MySQL 8.4 与七项远端门禁全绿。
- 检查点 31 已完成：默认关闭的 `RESOURCE_NOT_FOUND` 自动重放，数据库条件租约、批次/次数上限、指数退避抖动；到期拒绝快照清理且保留非敏感事实。本地 111 项后端发现/99 项执行、前端 13 项、JAR、真实 HTTP 和响应式页面已通过，[Run 35852483053](https://github.com/Trigger726/OnCall-Agent/actions/runs/35852483053) 的 MySQL 8.4 与七项门禁全绿。详见 [验收记录](acceptance/V1.7-checkpoint-31.md)。
- 检查点 32 已完成：真实 Alertmanager 0.34.1 以专用鉴权向 OpsPilot 投递 firing、resolved 和混合好坏项；隔离 Compose、无密钥诊断证据与 [Run 35853706878](https://github.com/Trigger726/OnCall-Agent/actions/runs/35853706878) 的八项远端门禁全绿。详见 [验收记录](acceptance/V1.7-checkpoint-32.md)。
- 检查点 33 已完成：Prometheus 真实抓取隔离测试指标并评估规则，将 firing/恢复经 Alertmanager 写入同一 OpsPilot Alert；[Run 35854884221](https://github.com/Trigger726/OnCall-Agent/actions/runs/35854884221) 的九项远端门禁全绿，含规则联调和容器健康烟测。详见 [验收记录](acceptance/V1.7-checkpoint-33.md)。
- 待完成：Runbook 命中率趋势、跨 Incident 语义相似/依赖共因聚类、真实外部提醒与回执；生产业务 exporter/服务指标与 Prometheus 规则联调，以及 SLO 出站通知、生产 recording rules/长期数据和低流量样本策略；Trace 的真实生产 Prometheus/Loki 联调、Collector 重启持久化与生产容量验证。
- 检查点 08 已完成：隔离提交后实时推送的运行时异常，验证同事务后续回调与所有推送失败时完整调查仍可完成、回放；4 项集成测试及远端四段式 CI 通过。
- 检查点 07 已完成：事件推送移至实际事务 afterCommit，新增外层提交/回滚和游标回放测试；定向回归与远端四段式 CI 通过，默认后端 44 项、2 项条件跳过。分布式可靠投递仍待实现。
- 检查点 06 已完成：修复取消排队调查后残留 Future 占用有界队列，补充保持 worker 阻塞时接纳替代任务的失败/成功对照；定向 3 项回归和远端四段式门禁通过。
- 将对话 SSE 升级为模型 Provider 原生 token 流，并验证断线取消与消息一致性。

简历价值：可讲分布式事件一致性、端到端可观测性和事故复盘闭环。
