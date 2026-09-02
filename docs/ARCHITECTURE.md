# OpsPilot 架构设计

## 1. 设计目标

OpsPilot 解决的是企业信息系统发生故障后的协同闭环，而不是物理设备预测性维护。系统必须回答九个问题：

1. 哪些原始事件属于同一问题？
2. 哪项业务服务受影响，依赖链是什么？
3. 当前谁负责，超时后通知谁？
4. Incident 当前处于哪个阶段，下一步允许做什么？
5. 根因判断引用了哪些告警、指标、日志、变更和手册证据？
6. 值班人员能否围绕同一 Incident 持续追问而不丢失证据上下文？
7. 调查连接断开后，能否恢复过程并保证结果继续落库？
8. 高风险建议由谁独立复核，如何避免自批和并发覆盖？
9. 谁在什么时间执行了什么操作？

## 2. 模块边界

| 模块 | 责任 | 不负责 |
| --- | --- | --- |
| Alert | 接入、校验、去重、指纹、压缩 | 人工处置状态 |
| Incident | 生命周期、负责人、时间线、乐观锁 | 采集外部监控数据 |
| CMDB | 资源、归属、依赖关系、变更 | 判断故障根因 |
| On-call | 排班、当前责任人、升级链 | Incident 业务状态 |
| Investigation | Agent 计划、只读工具执行、再规划、证据报告与轨迹查询 | 自动执行高风险变更 |
| Remediation | 高风险动作草案、独立审批、版本控制与治理留痕 | 直接调用生产执行器 |
| Postmortem | 脱敏证据快照、无责复盘、独立发布和防复发行动项 | 提前断言根因或替代行动项执行 |
| Assistant | 持久化会话、Incident 上下文、SSE 输出和证据引用 | 绕过状态机修改生产数据 |
| Audit | 记录关键操作 | 修改业务数据 |

当前采用模块化单体：同一进程内按领域包隔离，事务边界清晰，部署和演示成本低。若告警吞吐或组织规模增长，可优先拆分 Alert Intake 和 Notification，不需要先把所有模块改成微服务。

## 3. 告警到 Incident

```text
receive event
  -> validate source / severity / resourceCode
  -> locate CMDB resource
  -> external_event_id exact match?
       yes: occurrence_count + 1
       no: SHA-256(source|resource|severity|normalized title)
           -> match active event in 30-minute window?
                yes: occurrence_count + 1
                no: create alert_event
  -> resolve owning application through CMDB dependency
  -> find open Incident with same service + severity in 2-hour window
       yes: attach alert
       no: create Incident and notify current on-call
  -> append timeline evidence reference
```

外部事件 ID 解决监控平台重试；指纹解决不同事件 ID 或没有事件 ID 的重复告警。`occurrence_count` 保留原始噪声规模，因此压缩率可以计算而不是凭空宣称。

## 4. Incident 状态机

```text
OPEN -> ACKNOWLEDGED -> INVESTIGATING -> MITIGATED -> RESOLVED -> CLOSED
                                  ^            |
                                  +------------+
                                  ^
                                  +----- RESOLVED (reopen)
```

- 禁止 `OPEN -> RESOLVED` 等跳跃，避免缺少确认和调查记录。
- `MITIGATED -> INVESTIGATING` 支持缓解失败回退。
- `RESOLVED -> INVESTIGATING` 支持故障复发。
- 更新条件包含 `id AND version`。受影响行数为 0 时返回 `409 INCIDENT_VERSION_CONFLICT`，防止两名值班人员互相覆盖。

### 无责复盘与防复发行动

复盘不是调查阶段的即时总结。只有 `RESOLVED/CLOSED` Incident 才能创建，创建事务会先读取当时已有的时间线、告警、最近调查报告和相关变更，经过 `LogRedactor` 后保存 JSON 快照，再写 `POSTMORTEM_CREATED` 事件。因此后续新增时间线不会悄悄改变复盘依据，重复创建也只返回同一份草稿。

草稿包含事件摘要、用户/业务影响、直接与系统性原因、促成因素、经验与改进五类正文。任一字段为空或仍含 `【待补充】`，以及没有行动项时，均不能进入复核。行动项必须指向具有运维处置权限的活跃用户并设置不早于当天的截止日期；父复盘和子行动项分别带版本号，新增/编辑子项也先递增父版本，避免提交与修改并发穿透。

提交后正文和行动项冻结，只有 `ADMIN/OPS_MANAGER` 可以复核，且提交人不能复核自己的内容。`REQUEST_CHANGES` 回到草稿，`PUBLISH` 后正文永久只读；行动项仍由负责人或管理角色完成，并在 Incident 时间线和审计日志中留下引用。这个边界参考 [Google SRE 的 Postmortem Culture](https://sre.google/sre-book/postmortem-culture/) 对影响、原因、行动项、无责文化和正式复核的要求，以及 [FireHydrant retrospectives](https://docs.firehydrant.com/docs/conducting-retrospectives) 与 [follow-ups](https://docs.firehydrant.com/docs/managing-follow-ups) 对事故证据汇集、复盘后跟踪工作的划分。OpsPilot 实现的是本项目内的确定性治理流程，不宣称复制这些产品的全部能力。

## 5. 可解释 Agent 调查

一次调查生成独立 `agent_investigation_run`，由确定性编排器执行：

1. `PLAN`：根据 Incident 和主资源生成只读取证计划。
2. `EXECUTE / alert_snapshot`：读取关联告警和发生次数。
3. `EXECUTE / cmdb_topology`：查询上下游资源、方向和健康状态。
4. `EXECUTE / metrics_snapshot`：查询主资源及一跳依赖在故障窗口内的指标快照。
5. `EXECUTE / recent_change_correlation`：关联故障窗口前 4 小时至最近更新时间后 1 小时的变更。
6. `EXECUTE / log_search`：检索主资源及一跳依赖的错误、告警和信息日志，返回前统一脱敏。
7. `EXECUTE / runbook_retrieval`：根据 Incident、描述和告警症状，从当前操作者有权访问的已发布分块中执行 BM25 召回。
8. `REPLAN`：统计证据数量、类型和工具失败，决定继续取证还是形成结论。
9. `FINISH`：生成正式调查报告，并将报告与 Agent run 相互关联。

每个 `agent_investigation_step` 保存阶段、工具名、输入 JSON、`SUCCEEDED / NO_DATA / FAILED`、输出摘要、证据 JSON、错误原因和耗时。某个工具失败不会丢失已经取得的证据，run 标记为 `PARTIAL`；编排主链路失败则标记 `FAILED`。所有工具只读，建议动作不会自动执行生产变更。

规则引擎先生成假设、置信度和建议。启用模型时，DashScope 只负责把已有证据和规则假设组织为受约束摘要；结构化证据不会被模型输出覆盖。模型超时或失败时，事务继续保存规则结果。

这种设计将“模型回答”降级为可替换的叙述能力，把数据来源、业务状态和风险控制留在确定性代码中。

### 调查事件流与恢复

运行步骤和实时事件承担不同责任：`agent_investigation_step` 是最终工具执行账本，`agent_investigation_event` 是面向过程订阅的追加事件日志。服务端在推送前先提交事件，再用数据库事件 ID 作为 SSE `id`，因此客户端重连后可调用 `GET /api/v1/agent-runs/{runId}/events?after={eventId}` 回放缺失部分，而不依赖浏览器仍保持原连接。

调查请求进入有界 `ThreadPoolTaskExecutor`，避免高峰期无界创建线程。JWT 用户 ID 和请求 IP 在进入异步线程前捕获，保证安全上下文不会因线程切换丢失。客户端断开只关闭发送通道，不中断后台调查；run、报告、时间线和审计仍会完成。当前数据库事件日志适用于单实例部署，多实例事件广播需要 Redis Streams、Kafka 或数据库协调机制。

事件包括 `RUN_STARTED`、计划完成、步骤开始、证据采集、步骤失败、再规划、动作建议、运行完成和运行失败。页面展示的是系统已执行并落库的事件，不是模型隐式推理。

### 运行控制与并发边界

流式入口先执行 `prepare`，将 run 以 `QUEUED` 状态和 `RUN_QUEUED` 事件提交，再交给有界执行器。调用方可发送 `Idempotency-Key`；数据库约束保证同一 Incident 的同一键最多对应一个 run，重复请求只返回原 run 的持久化事件快照，并通过响应头标明回放。

每个 run 保存 `deadline_at`。请求级 `timeoutMs` 受服务端最大预算限制，单线程看门狗在截止时登记超时请求并尽力中断任务；显式取消接口同样先保存申请人、原因、时间和请求事件，再取消受管 `FutureTask`。编排器在工具边界检查终止请求，最终写入 `CANCELLED` 或 `TIMED_OUT`，且不生成报告和处置提案。外部客户端若不响应线程中断，Provider 自身的连接/读取超时仍限制单步阻塞时间。

执行器饱和时不丢失请求事实：run 转为 `QUEUE_REJECTED` 并追加 `RUN_REJECTED`。所有事件由 run 行上的 `next_event_sequence` 在事务中串行分配，避免工作线程与取消/超时线程竞争时出现重复序号。

### 可观测数据 Provider

指标和日志分别通过 `MetricsProvider`、`LogsProvider` 接口接入，Router 按优先级选择可用实现。默认本地 Provider 从 Flyway V4 的指标样本和日志事件表读取可复现证据；启用 `PROMETHEUS_ENABLED` 后，Prometheus Provider 优先调用 `/api/v1/query`；启用 `LOKI_ENABLED` 后，Loki Provider 优先调用 `/loki/api/v1/query_range`。任一外部 Provider 失败时携带降级原因回退到对应本地证据。

外部调用配置连接/读取超时，使用 Spring Retry 做单次请求内重试，并由 `ProviderGuard` 在多次请求间维护 `CLOSED / OPEN / HALF_OPEN` 状态。Agent 步骤保存 Provider、查询表达式、时间窗、外部引用和 warning，避免只保存一段不可溯源的自然语言。Loki 支持 `X-Scope-OrgID` 与 Bearer Token，按纳秒时间戳、返回上限和倒序查询日志 stream；常见 JSON 日志中的 message、level、logger、traceId 会转换为结构化证据。日志在进入证据链前屏蔽密码、Token、Authorization、邮箱和完整 IPv4；原始敏感值不写入调查报告。

适配器协议以 [Prometheus HTTP API](https://prometheus.io/docs/prometheus/latest/querying/api/) 和 [Grafana Loki HTTP API](https://grafana.com/docs/loki/latest/reference/loki-http-api/) 为准，并通过本机临时 HTTP 服务验证请求参数、请求头、响应格式和降级契约。

### Runbook 知识库与检索门禁

V1 的 `runbook` 表和浏览器 `contains` 搜索保留为迁移来源及效果基线。V1.6 新增稳定文档键与不可变版本：内容不变时按 SHA-256 哈希幂等复用，内容变化时创建下一版本并将旧版本标记为 `SUPERSEDED`。每个版本保存来源、资源/服务元数据和角色 ACL；Markdown 按标题分段并限制分块长度，PDF 由 PDFBox 提取文本后进入同一处理链。

```text
Markdown / extractable PDF
  -> validate size, type and metadata
  -> content hash + immutable version
  -> heading-aware chunks + role ACL
  -> role-filtered BM25 ranking ------------------+
  -> optional version-bound embedding + cosine --+-> RRF -> actual engine / ranks / warning
                                      unavailable +-> deterministic BM25 fallback
  -> score + runbook:{stableKey}:v{version}#chunk-{index}
  -> redact -> retained snapshot -> hidden first grade -> reviewer grade -> agreement / graded qrels
                    |                                      |
                    +-> timed payload purge -------------->+ qrels remain evaluable
```

BM25 使用小型本地语料实现，词元包含 ASCII 单词、中文单字和相邻二元组；标题与分段标题加权，结果按分数和 chunk ID 稳定排序。检索先做 ACL 过滤，因此无权文档不会进入候选集或分数统计。

V8 为每个不可变 chunk 保存 `provider + model + contentHash + dimensions + vector`，并单独记录索引构建运行。外部 Embedding 调用不占用数据库事务；全部 batch 通过数量、维度、有限值校验后，才在短事务内原子替换当前模型索引。`provider + model + 当前已发布 chunk 指纹` 相同则幂等复用；构建失败只把运行标记为 `FAILED`，不会先删除旧索引。新版本发布后若覆盖率低于门槛，查询不会混用残缺向量，而是显式降级 BM25。

混合检索分别取得 BM25 和余弦相似度名次，再以 Reciprocal Rank Fusion 合并：`score(d)=Σ 1/(k+rank_i(d))`，默认 `k=60`。这样不需要假设 BM25 原始分与余弦分处于同一量纲。返回值包含请求模式、实际引擎、词法/向量名次、向量覆盖率和 warning，Agent 与页面复用同一服务。该选择与 [Elastic 官方 Hybrid Search](https://www.elastic.co/docs/solutions/search/hybrid-search/) 和 [Elastic RRF API](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/reciprocal-rank-fusion) 的推荐一致；[Milvus Hybrid Search Retriever](https://milvus.io/docs/milvus_hybrid_search_retriever.md) 也以 dense/sparse + RRF 组合为标准路径。因此当前保留本地持久化向量适配，语料规模增长后可替换存储后端，不改变上层融合与评测契约。

固定评测保存数据集版本和每个引擎的 Recall@3、MRR、NDCG@3、首位稳定引用命中率、失败列表与 unavailable 原因，并在同一批查询上重放原版关键词 contains、BM25 和可用时的 Hybrid。Hybrid 只有在完整索引下成功跑完全部查询才计分，Provider 中途降级不会生成伪指标。Milvus 的[上下文检索示例](https://milvus.io/docs/contextual_retrieval_with_milvus.md)同样通过固定查询集和 Pass@K 对比不同检索配置，而不是仅展示成功样例。

V9 为控制台与 Agent 的真实检索保存不可变快照，包括查询哈希与原文、来源、角色、请求/实际引擎、向量状态与覆盖率、候选量、TopK、耗时和返回结果 JSON；离线评测继续调用不带追踪的内部入口，避免自己生成的查询污染样本。快照写入是 best-effort：遥测持久化故障不会阻断检索，响应的 `searchId` 为空时前端禁用反馈。提交人只能评价本人查询且只能选择快照中真实返回的 `stableKey`；等级为 0–3，可在待复核阶段修改。`ADMIN/OPS_MANAGER` 读取的队列排除本人提交项，复核 SQL 同时约束 `version_no` 和 `PENDING`，防止自审和并发覆盖。批准且等级不低于 2 的判断原子晋级为 `HUMAN_JUDGMENT` 评测 case；批准的负判断保留用于误召回分析，但不被错误转换成“预期命中”。

V10 把评测 case 的正相关等级 1–3 一并持久化。评测前先以查询分组，再以文档稳定键形成 qrels；同一查询可以有多个不同相关文档，同一 query-document 若来自多次独立判断则取等级均值。这样 `caseCount` 表示唯一查询数，`judgmentCount` 表示不同 query-document qrels 数，避免旧实现把每条判断当成独立查询而重复计权。Recall@3 计算每个查询召回的相关文档比例，MRR 取首个相关文档倒数排名，NDCG@3 使用 `gain=2^grade-1` 和对数位置折损，再除以该查询理想排序的 DCG。该定义遵循 [OpenSearch 搜索质量评估](https://docs.opensearch.org/latest/search-plugins/search-relevance/evaluate-search-quality/) 和 [Elasticsearch ranking evaluation](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/search-rank-eval) 的分级相关性与归一化排序思想；NDCG 能区分“都召回了，但高相关结果排错位置”，不能由 Recall/MRR 替代。

V11 将“审批判断”升级为可量化的双评分：待复核响应不返回提交人身份、原始等级或评论，只从不可变检索快照提取查询、文档标题、摘要和稳定引用；复核人必须独立给出 0–3 级，批准时该评分成为最终 qrel 等级。拒绝项没有可比较的第二评分，历史记录也不回填伪标签，因此二者都不进入一致性样本。系统同时计算精确一致率、相差不超过一级的比例和线性加权 Cohen's kappa：`κ = 1 - observedWeightedDisagreement / expectedWeightedDisagreement`，四级序数标签的权重为 `|i-j|/3`。当没有样本或两边标签都没有类别变化时 κ 返回 `null` 而不是误报 0。该定义与 [scikit-learn Cohen kappa](https://scikit-learn.org/stable/modules/generated/sklearn.metrics.cohen_kappa_score.html) 的双标注人、线性权重和未定义边界一致；当前不把少量隔离样本的 κ 当成生产标注质量。

V12 把检索遥测从“永久保存完整快照”改为显式生命周期。写入前对查询、结果中的标题/摘要/服务字段，以及评分评论和复核备注统一屏蔽密码、Token、Authorization、邮箱和完整 IPv4，并记录发生脱敏的字段数。默认保留 30 天，定时任务或管理员接口按批选择仍为 `ACTIVE` 的到期快照：先把未完成复核标为 `REJECTED/AUTO_EXPIRED_BY_RETENTION`，再清空自由文本评论，最后将查询正文、查询哈希、结果 JSON 和查询人替换为不可逆 `PURGED` 墓碑。操作只选 `ACTIVE`，因此重复执行幂等；每个有效批次写一条不含原文的清理审计。已批准的正相关 qrel 在复核时已经复制脱敏查询、稳定文档键和最终等级，所以原快照擦除后仍能参与离线评测。该取舍遵循 [OWASP Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html) 关于记录前排除/掩码敏感数据、测试日志机制和不得超期保存的建议；定时、批量、幂等保留任务参考 [Grafana Loki Compactor retention](https://grafana.com/docs/loki/latest/operations/storage/retention/) 的运行边界。当前不是物理删除整行，因为查询、判断、审计和 qrel 之间存在可追溯外键；擦除的是敏感 payload，保留的是非敏感结构事实。

这一模型对应 [OpenSearch Judgments](https://docs.opensearch.org/latest/search-plugins/search-relevance/judgments/) 的 query-document 相关性等级与显式/隐式判断边界，以及 [OpenSearch Query Sets](https://docs.opensearch.org/latest/search-plugins/search-relevance/query-sets/) 从真实用户查询构造评测集合的思路；其 [Search Relevance Workbench](https://docs.opensearch.org/latest/search-plugins/search-relevance/using-search-relevance-workbench/) 进一步把 query set、search configuration、judgment list 和 experiment 分离。OpsPilot 当前只实现业务所需的显式人工闭环，不用点击即相关的隐式假设，也不让 LLM 自动批准自己的标注。生产化仍需把保留期绑定真实法律/合同要求、细化部门/资源访问范围，并覆盖数据库备份和导出副本的同等删除策略。

设计还借鉴了 [Backstage TechDocs](https://backstage.io/docs/features/techdocs/) 的 docs-like-code 与可搜索文档思路、[Rundeck](https://docs.rundeck.com/docs/about/introduction.html) 的 Runbook 自动化权限/历史边界，以及 [OpenSearch BM25](https://docs.opensearch.org/latest/im-plugin/similarity/) 的关键词检索模型。当前仍是单机小语料与可选外部 Embedding：没有向量 ANN/OpenSearch，未接 cross-encoder rerank，PDF 不含 OCR，导入即发布且没有内容审核流。

## 6. OnCall 多轮协作

每个会话归属一个登录用户，并可选绑定一个 Incident。绑定后，服务端自动注入 Incident 元数据、关联告警、主资源及一跳依赖的近期变更、调查报告和时间线；前端不能自行拼接或伪造证据上下文。

消息写入 `assistant_message`，最近 12 条作为模型对话窗口。上下文同时携带最新 Agent run，值班人员可以从助手启动调查并追问真实执行轨迹。对话 SSE 使用 POST + JWT，返回 `meta / delta / done / error` 事件；异步分派仅放行 Spring MVC 内部的 `ASYNC/ERROR` dispatcher，请求入口仍必须完成 JWT 鉴权。模型不可用时由证据规则回答，流式协议、历史记录和导出能力保持可用。对话当前仍是完整回答生成后的协议分块；从助手触发的 Agent 调查则复用 Investigation 的真实持久化事件流。

调查报告负责生成一次性的结构化研判快照；OnCall 助手负责在同一证据边界内持续追问。两者分开可以避免聊天内容反向覆盖正式调查结论。

## 7. 受控处置、权限与审计

当调查置信度不低于 0.80 且证据包含故障窗口变更时，系统可生成 `ROLLBACK_CHANGE` 高风险提案。提案保存目标资源、关联变更、证据引用、发起人、状态和版本，但不包含可直接执行的生产凭证。

```text
Agent evidence -> PENDING_APPROVAL -> APPROVED
                                  \-> REJECTED
```

- 只有 `ADMIN` 或 `OPS_MANAGER` 可以审批。
- `requested_by == reviewed_by` 时返回 403，强制独立复核。
- 更新条件包含 `version` 和 `PENDING_APPROVAL`，并发或重复审批返回 409。
- 提案创建和审批分别写入 Incident 时间线与 `audit_log`。
- `APPROVED` 仅表示治理检查通过；当前版本没有生产执行器，不会自动回滚、扩容或重启。

| 角色 | 读取 | Incident 处置 | 调查 | 高风险审批 | 审计日志 |
| --- | --- | --- | --- | --- | --- |
| ADMIN | 全部 | 是 | 是 | 是，不能自批 | 是 |
| OPS_MANAGER | 全部运行数据 | 是 | 是 | 是，不能自批 | 是 |
| ON_CALL | 运行数据 | 是 | 是 | 否 | 否 |
| AUDITOR | 运行与审计 | 否 | 否 | 否 | 是 |

密码使用 BCrypt，JWT 包含用户 ID 与角色但每次请求仍重新加载有效用户，停用账号后旧 Token 不会继续获得权限。关键状态流转、分派、备注和调查均写入 `audit_log`。

## 8. 数据模型

主要关系：

```text
sys_user 1---n cmdb_resource(owner)
cmdb_resource n---n cmdb_resource (cmdb_relation)
cmdb_resource 1---n change_record
cmdb_resource 1---n observability_metric_sample
cmdb_resource 1---n observability_log_event
cmdb_resource 1---n incident
incident 1---n alert_event
incident 1---n incident_timeline
incident 1---n investigation_report
incident 1---n agent_investigation_run 1---n agent_investigation_step
agent_investigation_run 1---n agent_investigation_event (serialized sequence)
agent_investigation_run n---0..1 investigation_report
incident 1---n remediation_proposal n---1 agent_investigation_run
remediation_proposal n---1 change_record
sys_user 1---n remediation_proposal(requested/reviewed)
incident 1---0..1 incident_postmortem 1---n postmortem_follow_up
sys_user 1---n incident_postmortem(created/submitted/reviewed)
sys_user 1---n postmortem_follow_up(owner/created/completed)
sys_user 1---n assistant_session 1---n assistant_message
incident 1---n assistant_session (optional context)
runbook_document(stable_key) 1---n immutable versions
runbook_document 1---n runbook_chunk
runbook_document 1---n runbook_document_acl(role)
sys_user 1---n runbook_retrieval_query(created_by)
runbook_retrieval_query 1---n runbook_relevance_judgment
runbook_relevance_judgment 0..1---1 runbook_retrieval_eval_case
runbook_retrieval_eval_case set ---> runbook_retrieval_eval_run(dataset snapshot)
cmdb_resource 1---n oncall_schedule 1---n oncall_shift
cmdb_resource 1---n escalation_policy 1---n escalation_step
```

数据库变更由 Flyway 管理。H2 使用 MySQL 兼容模式保证本地零配置体验，Compose 提供 MySQL 部署路径；Testcontainers 已在真实 MySQL 8.4 上从空库执行 V1–V13，并验证关键索引、中文数据、Runbook 召回、调查主链路和复盘发布/行动项闭环。`flyway-mysql` 作为正式运行依赖加载 MySQL 方言支持；最新直接证据以验收报告为准。

## 9. 可观测性和失败策略

- `/actuator/health`：存活与依赖健康。
- `/actuator/prometheus`：JVM、HTTP、连接池等指标。
- `/api/v1/observability/providers`：Provider 启用状态、优先级和熔断状态。
- API 错误统一返回 `success/data/error/timestamp`。
- 参数错误返回 400，认证失败 401，权限不足 403，状态/版本冲突 409。
- AI 不可用不影响告警、Incident 和审计主链路。
- 工具无数据与工具失败明确区分；失败原因随步骤持久化，其他证据源继续执行。
- Agent 事件先落库后发送，客户端断开不取消后台调查，并可按事件 ID 回放。
- Agent 调查使用幂等键、有界队列、截止时间和显式取消控制；取消、超时和拒绝均进入可审计终态。
- Prometheus/Loki 失败由超时、重试、跨请求熔断和本地 Provider 降级保护；日志进入证据链前脱敏。
- 高风险提案只能独立审批，使用 RBAC、自批禁止和乐观锁保护；审批不会自动触发生产变更。

## 10. 交付与回归门禁

- 默认 Maven 套件以 H2 覆盖领域规则、HTTP API、事件回放、运行控制、审批和外部 Provider 契约；MySQL Testcontainers 用系统属性显式启用，避免开发机没有 Docker 时误报失败。
- GitHub Actions 独立执行前端生产构建、H2 测试与 JAR、MySQL 8.4 集成测试，只有三者通过后才构建容器镜像。
- 镜像门禁不止检查 `docker build`：还以非 root `opspilot` 用户启动容器，并轮询 `/actuator/health`。镜像预建可写 `/app/data`，保证默认 H2 数据文件能在最小权限下创建。
- SSE emitter 断开测试确认网络连接消失只停止发送，不把异常传播到后台调查；有界执行器测试用一个工作线程和一个队列槽位稳定复现排队、取消和第三个请求拒绝。

## 11. 后续演进

1. Alert Intake 前置 Kafka，用唯一键与消费者幂等扩展吞吐。
2. 通知模块接企业微信、短信或邮件，并实现确认回执和定时升级任务。
3. 将数据范围权限细化到部门、系统和资源负责人。
4. 增加系统级并发压测、真实 socket 断流恢复，以及外部 Provider 组合故障注入。
5. 为多实例事件广播和任务协调接入消息组件。
6. 将对话 SSE 从完整回答分块升级为模型 Provider 原生 token 流。
7. 在已完成 Postmortem 主链路之上加入 SLA/SLO、MTTA/MTTR、重复事故分析和行动项逾期升级。
8. 从真实但脱敏的历史 Incident/查询流量持续扩充已实现的双评分 qrels，加入第三方仲裁、超过两名标注人的一致性和分层抽样；将现有保留任务扩展到备份/导出副本和面向单条数据的受控删除，再以 NDCG/Recall 验证真实 Embedding 与 cross-encoder rerank 是否稳定优于 BM25/RRF，决定是否引入 ANN/OpenSearch/Milvus。
