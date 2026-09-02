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
- 检查点 04 已完成：多相关文档集成测试、全量 30 项测试、前端生产构建和最新 JAR 启动通过；真实 MySQL V10 仍因本机 Docker Desktop 引擎不可用待复验。
- 检查点 05 已完成：Flyway V11 保存独立复核评分；待办接口隐藏原始等级、评论和提交人，只提供查询与不可变结果快照，减少锚定偏差。
- 检查点 05 已完成：复核评分作为最终 qrel 等级，拒绝样本与历史单评分记录不进入一致性统计；页面展示精确一致率和线性加权 Cohen's kappa，并正确处理空集/无类别变化时 κ 未定义。
- 检查点 05 已完成：公式单元测试与 API 集成测试通过，全量增至 33 项；前端生产构建和 V11 JAR/API 烟测通过，真实 MySQL V11 仍因 Docker Desktop 引擎不可用待复验。
- 待完成：从真实但脱敏的历史 Incident、Runbook 维护记录和查询流量持续积累双评分样本，补保留/删除策略和查询频率/故障类型分层；样本量扩大或标注人超过两人后再引入第三方仲裁/多标注人指标，并验证真实 Embedding 与 cross-encoder rerank 是否稳定优于 BM25/RRF。

简历价值：用可复现评测替代“RAG 效果很好”的空泛描述。

## 后续：V1.7 分布式事件与复盘指标

- 使用 Redis Streams 或 Kafka 分发多实例 Agent 事件，保留数据库作为回放与审计事实源。
- 接入 OpenTelemetry trace，串联告警接入、Agent run、工具步骤和外部 provider。
- 生成 Postmortem、MTTA/MTTR、重复事故和 Runbook 命中率看板。
- 将对话 SSE 升级为模型 Provider 原生 token 流，并验证断线取消与消息一致性。

简历价值：可讲分布式事件一致性、端到端可观测性和事故复盘闭环。
