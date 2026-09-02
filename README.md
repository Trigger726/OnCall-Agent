# OpsPilot

企业级智能运维与故障闭环平台，面向能源企业信息系统的告警治理、Incident 协同、值班升级和证据驱动调查。

OpsPilot 不是“输入一条告警让大模型猜根因”的聊天演示。它先把资源、告警、事故、人员、变更和审计放进同一个业务闭环，再将 AI 限制在可引用、可追踪的证据上下文中。

## 核心能力

- 告警治理：外部事件 ID 去重、SHA-256 指纹压缩、30 分钟窗口聚合、原始告警与 Incident 分层。
- Incident 工作台：`OPEN -> ACKNOWLEDGED -> INVESTIGATING -> MITIGATED -> RESOLVED -> CLOSED` 状态机、乐观锁、分派、备注和时间线。
- CMDB：应用、API、数据库和中间件台账，依赖/调用关系拓扑，事故与近期变更关联。
- 值班升级：服务排班、当前值班人、分级升级策略和通知留痕。
- 可解释 Agent 调查：以 `PLAN -> EXECUTE -> REPLAN -> FINISH` 编排告警、CMDB、指标、变更、日志和 Runbook 六个只读工具；每步持久化输入、查询范围、数据源、证据、失败原因和耗时。
- 可恢复调查事件流：运行事件先落库再通过 SSE 实时发送，事件 ID 同时作为断线回放游标；客户端退出不取消后台调查，结果仍会完整进入时间线和审计。
- Agent 运行控制：同一 Incident 使用幂等键抑制重复 run；任务先进入有界队列，可显式取消并受截止时间预算约束；取消、超时和队列拒绝都形成可回放的持久化终态。
- Runbook 知识库：Markdown/PDF 入库、内容哈希幂等、不可变版本、角色 ACL 和标题分块；本地 BM25 与可选 DashScope 向量召回通过 RRF 融合，返回 `runbook:{stableKey}:v{version}#chunk-{index}` 稳定引用。向量未启用、覆盖不足或 Provider 失败时显式降级 BM25；真实检索快照在入库前脱敏并按可配置保留期自动擦除，提交人与复核人分别给出 0–3 级评分，复核前隐藏原始等级，并以线性加权 Cohen's kappa 量化一致性；批准后的分级 qrels 在原快照清理后仍可按唯一查询计算 Recall@3、MRR、NDCG@3 和引用命中率。
- 可观测数据适配：统一 Metrics/Logs Provider SPI；默认使用可复现的本地证据库，可选调用 Prometheus 与 Loki HTTP API，外部失败后自动重试、熔断并降级到本地证据。
- 证据报告：规则引擎离线生成假设、置信度和建议，可选 DashScope 生成受约束摘要；单工具失败时保留其他证据并降级为部分完成。
- OnCall 助手：持久化多轮会话、SSE 流式输出、Incident 上下文绑定、证据引用、会话清空/删除和 Markdown 导出。
- 受控处置：高置信度变更关联可生成回滚草案；管理员或运维经理独立审批，禁止申请人自批，并用乐观锁防止并发覆盖。审批只解除治理门禁，不自动修改生产环境。
- 无责事故复盘：只有已恢复/已关闭 Incident 才能从当时的时间线、告警、调查报告和变更引用生成脱敏快照；五类复盘内容必须补全，并绑定有负责人和期限的防复发行动项后才能提交。提交人不能自审，发布后正文冻结，行动项仍可由负责人闭环，全部过程进入时间线和审计。
- 事故运营分析：按创建窗口与严重等级计算 MTTA/MTTM/MTTR 的均值、中位数和独立样本数，排除缺失与负时长，并提供慢事故下钻；跨 Incident 管理行动项、逾期天数和持久化升级事实，重复扫描幂等、完成后关闭且不冒充外部通知送达。
- 安全审计：JWT、BCrypt、角色权限、关键操作审计、Prometheus 指标和健康检查。
- 运维控制台：Vue 3 + TypeScript，高密度桌面工作台及移动端响应式视图。

## 技术栈

| 层次 | 技术 |
| --- | --- |
| 后端 | Java 17, Spring Boot 3.2, Spring Security, Spring Retry, JdbcClient, Flyway, Apache PDFBox |
| AI | Spring AI Alibaba / DashScope，可选启用 |
| 数据 | H2 本地零配置，MySQL 8.4 生产化部署 |
| 前端 | Vue 3, TypeScript, Vite, Vue Router, Lucide |
| 可观测性 | Spring Boot Actuator, Micrometer, Prometheus, Loki |
| 工程化 | Maven Wrapper, Docker multi-stage build, Docker Compose, JUnit 5, MockMvc, Testcontainers, GitHub Actions |

## 架构

```text
Prometheus / APM / manual event
              |
              v
       Alert Intake API
              |
    dedupe + fingerprint + group
              |
              v
         Incident domain <------ CMDB topology
          |     |     |               |
          |     |     +---------- change records
          |     +---------------- on-call escalation
          +---------------------- timeline / audit
              |
              v
 Agent run: PLAN -> 6 read-only tools -> REPLAN -> FINISH
              |                         |
              v                         v
      evidence report        persisted step + event log
              |                  |               |
              |                  |               +--> SSE + cursor replay
              |                  v
              +--------> remediation proposal --> independent approval
              |
              +----> OnCall conversation (history + SSE + evidence refs)
              |
              +----> resolved incident -> evidence snapshot -> blameless postmortem
                                                      |            |
                                                      +-> follow-up +-> independent publish

 Observability providers: Prometheus -> local metrics / Loki -> local logs
 Agent controls: idempotency -> bounded queue -> deadline / cancel -> terminal event
 Runbook retrieval: immutable ACL chunks -> BM25 + optional vectors -> RRF / fallback -> citation
                         |                                      |
                         +-> redact -> retained snapshot -> blind review -> graded qrels
                                             |                    |
                                             +-> timed purge ---->+ (qrels survive)
```

详细设计见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 快速启动

前置条件：JDK 17+、Node.js 20+。

```bash
cd web
npm ci
npm run build
cd ..
./mvnw spring-boot:run
```

Windows PowerShell 使用：

```powershell
cd web
npm ci
npm run build
cd ..
.\mvnw.cmd spring-boot:run
```

打开 [http://localhost:9900](http://localhost:9900)。默认使用本地 H2 文件数据库，首次启动由 Flyway 自动建表和写入演示数据，不需要安装 MySQL 或配置 AI Key。

### 演示账号

所有账号的初始密码均为 `OpsPilot@2026`。

| 账号 | 角色 | 典型权限 |
| --- | --- | --- |
| `admin` | ADMIN | 全部功能 |
| `zhangwei` | ON_CALL | 事故处置与调查 |
| `lina` | OPS_MANAGER | 运行管理与事故处置 |
| `auditor` | AUDITOR | 审计只读，不能流转 Incident |

## Docker 部署

```bash
docker compose up --build -d
```

- OpsPilot: [http://localhost:9900](http://localhost:9900)
- Prometheus: [http://localhost:9090](http://localhost:9090)
- MySQL 仅在 Compose 内网开放。

生产部署前必须替换 `JWT_SECRET`、数据库口令和演示账号密码；不要将 `.env` 或真实 `DASHSCOPE_API_KEY` 提交到仓库。

## AI 模式

核心流程默认不依赖外部模型：

```bash
AI_ENABLED=false
```

启用 DashScope：

```bash
AI_ENABLED=true
DASHSCOPE_API_KEY=your-key
```

启用后，模型只能基于系统已收集的结构化证据生成研判摘要和对话回答。证据、规则假设、置信度和建议仍单独持久化，模型失败会自动回退到规则结果；OnCall 对话在没有 Key 时仍可回答告警、变更、状态和下一步动作。

Runbook 语义索引与生成式 AI 独立开关。默认仍使用零外部依赖的 BM25；需要验证真实向量召回时配置：

```bash
RUNBOOK_SEMANTIC_ENABLED=true
RUNBOOK_EMBEDDING_MODEL=text-embedding-v4
DASHSCOPE_API_KEY=your-key
```

管理员或运维经理再从“处置手册”执行索引重建。系统先在事务外批量生成向量，校验数量、维度和有限值，再在单个事务中原子替换当前 `provider + model` 索引；内容指纹未变化时幂等复用。重建失败保留上一版索引，但查询仅在已发布可见分块覆盖率达到门槛时进入 Hybrid，否则返回明确降级原因。

## 外部可观测数据接入

本地演示默认关闭外部依赖。需要查询 Prometheus 时配置：

```bash
PROMETHEUS_ENABLED=true
PROMETHEUS_BASE_URL=http://localhost:9090
PROMETHEUS_QUERY_TEMPLATE='up{job="%s"}'
```

查询表达式、故障时间窗、Provider、外部引用和降级信息会进入 Agent 步骤轨迹。Prometheus 超时或连续失败时由重试、熔断和本地指标 Provider 保持调查可用。

接入 Loki：

```bash
LOKI_ENABLED=true
LOKI_BASE_URL=http://localhost:3100
LOKI_QUERY_TEMPLATE='{resource_code="%s"}'
LOKI_TENANT_ID=energy-prod
LOKI_BEARER_TOKEN=your-token
```

Loki Provider 使用 `query_range` 查询故障时间窗，支持租户头和 Bearer Token，解析 stream labels 与常见 JSON 日志字段，并在形成证据前脱敏。Loki 超时、协议异常或熔断时会切换本地日志 Provider；实际 Provider 和 warning 均写入步骤轨迹。外部凭证只通过环境变量注入，不进入状态接口或调查报告。

## Agent 运行控制

流式调查请求支持 `Idempotency-Key` 和可选 `timeoutMs`。同一 Incident 使用相同键重试时返回原 run，不重复生成报告或处置提案；运行达到终态后前端清理该键，下一次人工运行会创建新 run。

```bash
AGENT_EXECUTION_TIMEOUT=60s
AGENT_MAX_EXECUTION_TIMEOUT=5m
AGENT_CORE_POOL_SIZE=2
AGENT_MAX_POOL_SIZE=4
AGENT_QUEUE_CAPACITY=50
```

运行先持久化为 `QUEUED`，随后进入 `RUNNING`。终态包括 `COMPLETED / PARTIAL / FAILED / CANCELLED / TIMED_OUT / QUEUE_REJECTED`。外部调用若不能立即响应线程中断，仍受 Provider 连接/读取超时约束，并在下一个安全执行边界完成取消或超时落库。

## Runbook 知识库与检索评测

管理员或运维经理可粘贴 Markdown，或上传最大 5 MB 的 `.md/.markdown/.pdf` 文件。相同 `stableKey + contentHash` 幂等复用；内容变化生成新版本并将旧版本标为 `SUPERSEDED`，历史引用不会被覆盖。PDF 当前只处理最多 200 页的可提取文本，不做扫描件 OCR。

检索先按当前登录角色过滤文档 ACL，再运行 BM25。若当前模型索引完整且 Provider 可用，系统追加余弦向量排序，并用 RRF（`rankConstant=60`）融合两个名次列表；RRF 不直接混合量纲不同的 BM25 原始分和余弦分。`AUTO / BM25 / HYBRID` 三种模式便于在线对照，返回实际执行引擎、词法/向量名次、索引覆盖率和降级 warning。

固定集已从 3 条扩充到 13 条种子改写查询，覆盖 Redis、接口延迟、认证、Kafka、MySQL 和 Kubernetes。当前默认离线演示中，`LEGACY_CONTAINS_V1` Recall@3/MRR/NDCG@3 为 0，`BM25_LOCAL_V1` Recall@3 为 1、MRR 为 0.961538、NDCG@3 为 0.971610、首位稳定引用命中率为 0.923077。Hybrid 只在真实索引完整并成功跑完全部查询时计分；无 Key 的默认演示会显示 unavailable。受控 `EmbeddingModel` 测试替身只验证索引、融合和降级契约，不冒充真实模型质量结论。

控制台和 Agent 的真实检索会保存查询、角色、请求/实际引擎、向量状态、耗时与返回结果快照；离线评测调用不记入查询日志，避免评测流量污染真实样本。查询本人只能评价快照中实际返回的文档，管理员或运维经理不能复核自己的判断，并以版本号阻止并发覆盖。待办只向复核人展示查询和当时的标题、摘要、引用，不暴露提交人身份、原始等级或评论；复核人独立给出 0–3 级，复核等级作为最终 qrel 等级，达到 2 才生成 `HUMAN_JUDGMENT` case。系统统计精确一致率、相差不超过一级的比例和线性加权 Cohen's kappa；拒绝样本及没有第二评分的历史记录不混入统计。评测时再把相同查询的多个相关文档聚合成 qrels，同一 query-document 的多个最终等级取平均，避免重复计权。当前闭环证明数据治理流程，不代表已经积累了生产规模标注。

查询文本和结果快照在写入数据库前统一屏蔽密码、Token、Authorization、邮箱和完整 IPv4，评分评论与复核备注也经过同一清洗。默认保留 30 天，每日定时按批处理到期记录；清理会把查询正文、哈希、结果 JSON 和查询人擦成不可逆墓碑，将尚未完成的复核自动拒绝并写入审计。已经晋级的 qrel 复制了脱敏查询、稳定文档键和最终等级，因此快照清理不会破坏后续评测。可通过 `RUNBOOK_RETRIEVAL_RETENTION`、`RUNBOOK_RETRIEVAL_CLEANUP_BATCH_SIZE` 和 `RUNBOOK_RETRIEVAL_CLEANUP_CRON` 调整策略。

## 关键接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/auth/login` | 登录并签发 JWT |
| GET | `/api/v1/dashboard` | 运行指标总览 |
| POST | `/api/v1/alerts/intake` | 接入告警并执行去重/聚合 |
| GET | `/api/v1/incidents/{id}` | Incident、告警、时间线与报告 |
| POST | `/api/v1/incidents/{id}/transitions` | 受状态机与乐观锁保护的流转 |
| POST | `/api/v1/incidents/{id}/investigations` | 运行可追踪 Agent 调查并生成报告 |
| POST | `/api/v1/incidents/{id}/investigations/stream` | 接收幂等键与可选超时预算，返回持久化 SSE 事件 |
| GET | `/api/v1/incidents/{id}/agent-runs` | 查询工具级调查轨迹 |
| GET | `/api/v1/agent-runs/{runId}/events?after={eventId}` | 按事件游标回放调查过程 |
| POST | `/api/v1/agent-runs/{runId}/cancel` | 显式取消排队中或运行中的调查 |
| GET | `/api/v1/incidents/{id}/remediation-proposals` | 查询 Incident 的受控处置提案 |
| POST | `/api/v1/remediation-proposals/{id}/reviews` | 独立批准或拒绝高风险提案 |
| GET/POST | `/api/v1/incidents/{id}/postmortem` | 读取或从已恢复 Incident 的脱敏证据生成复盘草稿 |
| PATCH | `/api/v1/postmortems/{id}` | 以乐观锁更新复盘五类正文 |
| POST | `/api/v1/postmortems/{id}/submit` | 校验正文和行动项后提交独立复核 |
| POST | `/api/v1/postmortems/{id}/reviews` | 管理员/运维经理发布或退回复盘，禁止提交人自审 |
| POST/PATCH | `/api/v1/postmortems/{id}/follow-ups`、`/api/v1/postmortem-follow-ups/{id}` | 创建或更新带负责人、期限和版本的防复发行动项 |
| POST | `/api/v1/postmortem-follow-ups/{id}/complete` | 负责人或管理角色完成行动项并写入证据链 |
| GET | `/api/v1/analytics/incidents` | 按日期/严重等级读取 MTTA、MTTM、MTTR、样本数、分布、慢事故和当前行动项摘要 |
| GET | `/api/v1/postmortem-follow-ups` | 按本人/全部、状态与逾期筛选跨 Incident 行动项 |
| POST | `/api/v1/postmortem-follow-ups/escalations/run` | 管理员/运维经理按业务日期幂等生成逾期升级事实 |
| GET | `/api/v1/observability/providers` | 查询指标/日志 Provider 与熔断状态 |
| GET | `/api/v1/runbooks/search?q={query}&topK=3&mode=AUTO` | 按角色过滤并返回 BM25/Hybrid 实际引擎、排名轨迹、降级说明和稳定引用 |
| POST | `/api/v1/runbooks/searches/{searchId}/judgments` | 查询本人对快照返回文档提交 0–3 级相关性判断 |
| GET | `/api/v1/runbooks/judgments/pending` | 管理员/运维经理读取排除本人、隐藏原始评分的待复核快照 |
| GET | `/api/v1/runbooks/judgments/agreement` | 读取双评分样本数、精确/相邻一致率和线性加权 κ |
| POST | `/api/v1/runbooks/judgments/{id}/reviews` | 提交复核评分并批准/拒绝；最终等级 ≥ 2 才进入评测集 |
| GET | `/api/v1/runbooks/searches/retention` | 管理员/运维经理读取快照保留期、活跃/到期/已擦除数量和脱敏字段计数 |
| POST | `/api/v1/runbooks/searches/retention/purge` | 按保留策略幂等清理一批到期快照并记录审计 |
| GET | `/api/v1/runbooks/{stableKey}/versions` | 查询可访问的不可变版本历史 |
| POST | `/api/v1/runbooks/imports/markdown` | 管理员/运维经理导入并发布 Markdown |
| POST | `/api/v1/runbooks/imports/file` | 管理员/运维经理上传 Markdown/PDF |
| POST | `/api/v1/runbooks/evaluations` | 运行并保存固定检索评测 |
| GET | `/api/v1/runbooks/evaluations/latest` | 读取最近一次评测 |
| GET | `/api/v1/runbooks/semantic-index` | 查询当前模型的向量覆盖率和最近构建状态 |
| POST | `/api/v1/runbooks/semantic-index/rebuild` | 管理员/运维经理幂等、原子重建向量索引 |
| GET/POST | `/api/v1/assistant/sessions` | 查询或创建持久化会话 |
| POST | `/api/v1/assistant/sessions/{id}/stream` | SSE 流式多轮对话 |
| GET | `/api/v1/assistant/sessions/{id}/export` | 导出 Markdown 对话记录 |
| GET | `/api/v1/cmdb/topology` | 服务依赖拓扑 |
| GET | `/api/v1/on-call/current` | 当前值班人 |
| GET | `/api/v1/audit-logs` | 操作审计 |

Swagger UI: [http://localhost:9900/swagger-ui/index.html](http://localhost:9900/swagger-ui/index.html)

## 测试

```bash
cd web && npm run build
cd .. && ./mvnw test

# 需要本机 Docker；在真实 MySQL 8.4 上执行 V1-V14 迁移和关键业务链路
./mvnw -Dopspilot.mysql.it.enabled=true -Dtest=MySqlCompatibilityIntegrationTest test
```

测试覆盖：

- Incident 合法/非法状态流转。
- 登录、JWT 与审计角色 403。
- 乐观锁版本冲突。
- 指纹告警的首次创建与重复压缩。
- 总览、CMDB 拓扑和 Incident 详情接口。
- OnCall 会话持久化、Incident 上下文、SSE 完成事件、证据引用和跨用户隔离。
- Agent 调查运行落库、9 步执行轨迹、六类数据源、Incident/OnCall 同源回读和运行证据引用。
- 18 条成功调查事件的 SSE 输出、持久化顺序和 `after` 游标回放。
- 调查幂等重试、排队取消、运行中取消竞态、超时预算、线程中断和队列拒绝终态。
- SSE 客户端断开隔离，以及单 worker/单队列槽位下的多请求饱和和排队取消。
- 高风险处置提案生成、角色限制、自批禁止、审批版本冲突和独立账号审批。
- Provider 状态、Prometheus/Loki 关闭时的本地路由、日志敏感字段脱敏。
- Provider 重试、熔断、半开探测和恢复状态机。
- Prometheus instant query、Loki range query 的真实 HTTP 请求/响应契约，以及外部日志失败后的路由降级。
- Runbook 中文/英文分词、Markdown 分块、PDF 文本抽取、内容幂等、不可变版本、角色 ACL、稳定引用和 13 条新旧检索对照评测。
- 向量索引内容指纹幂等、RRF 稳定融合、完整覆盖门槛、显式 BM25 降级，以及 Provider 失败不覆盖旧索引。
- 真实检索快照、结果范围校验、查询归属、0–3 级相关性判断、角色限制、自审禁止、复核版本冲突、正/负样本分流和离线评测流量隔离。
- 同一查询多相关文档聚合、分级 qrels、重复标注平均、查询/qrels 双计数，以及 Recall@3、MRR、NDCG@3 的精确回归值。
- 复核评分必填、原始评分/提交人盲化、最终等级晋级、拒绝样本隔离、线性加权 κ 公式及空/无类别变化边界。
- 检索查询/结果/评论入库前脱敏、保留期权限、过期待办自动拒绝、批量清理幂等、擦除审计，以及原始快照清理后 qrel 继续可用。
- 复盘仅在 Incident 恢复后生成、证据快照写前脱敏、草稿完备门禁、不可自审、退回再提交、发布冻结、父子版本冲突、行动项责任权限和时间线/审计留痕。
- MTTA/MTTM/MTTR 均值、中位数、独立分母、日期/严重等级筛选、缺失/负时长排除、慢事故下钻和 SPA 深链。
- 跨 Incident 行动项筛选、截止当天边界、逾期天数、扫描角色限制、唯一升级事实、重复扫描幂等和完成后关闭。
- MySQL 8.4 Testcontainers：Flyway V1-V14、中文数据、幂等复合唯一索引、Runbook BM25、完整 9 步/18 事件调查、复盘发布，以及逾期扫描/行动项完成闭环。

默认后端套件发现 37 项测试：36 项执行通过，1 项 Docker-MySQL 条件测试默认跳过。另行启用条件测试后，MySQL 8.4 已从空库执行 Flyway V1–V14，并验证到期快照清理与重复执行幂等、中文数据、Runbook 检索、完整调查链路、复盘发布、逾期扫描幂等和行动项关闭。Flyway 9.22.3 会提示其官方测试上限为 MySQL 8.0，后续应升级依赖并继续保留真实数据库门禁。GitHub Actions 将前端构建、H2 后端测试与 JAR、MySQL Testcontainers、容器构建与健康启动拆成四个门禁。阶段性运行与界面证据见 [docs/acceptance/README.md](docs/acceptance/README.md)。

## 目录

```text
src/main/java/org/trigger/opspilot/
  alert/          告警接入、去重和聚合
  incident/       Incident 状态机与时间线
  investigation/ Agent 编排、只读工具、执行轨迹、规则结论和可选 AI 摘要
  remediation/   高风险处置提案、独立审批与并发控制
  postmortem/    无责复盘、独立发布、防复发行动项与逾期升级
  analytics/     事故响应指标、样本口径、严重等级分布与慢事故下钻
  observability/ Metrics/Logs Provider、Prometheus/Loki 适配、可靠性保护和日志脱敏
  runbook/       文档版本/ACL、BM25/向量 RRF、检索快照、相关性复核和评测
  assistant/      多轮会话、Incident 上下文、SSE 与导出
  cmdb/           资源台账和依赖拓扑
  oncall/         排班与升级策略
  security/       JWT 和 RBAC
  audit/          操作审计
web/              Vue 3 运维控制台
deploy/           Prometheus 配置
docs/             架构、演示、面试材料和阶段验收报告
```

## 项目来源

OpsPilot 是在原 OnCall AI Agent 原型上进行的独立重构。重构保留并升级了原版多轮对话能力，同时补齐 Incident、CMDB、状态机、值班和审计闭环。原始版本保存在 Git 分支 `archive/oncall-original-2026-08-19`，新实现位于 `feat/opspilot-v1`，两者可以独立查看和演示。

License: MIT
