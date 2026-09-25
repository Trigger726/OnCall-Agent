# OpsPilot 10 分钟演示脚本

## 演示目标

让面试官在 10 分钟内看到：业务问题、领域建模、并发控制、多轮 Agent 协作、AI 约束、安全审计和可落地部署。

## 0:00 - 1:00 项目定位

打开运行总览：

> 这是一个面向能源企业信息系统的故障闭环平台。监控平台产生的是 Alert，运维团队处理的是 Incident，两者不能混为一谈。系统把 CMDB、值班、变更、调查和审计串起来，再把 AI 放进可追踪证据链中。

指出活跃 Incident、触发中告警、压缩率和平均确认时间。

## 1:00 - 2:20 告警降噪

进入“告警中心”，点击“接入告警”，连续提交两次相同事件：

- 第一次返回 `CREATED`，创建告警并聚合到 Incident。
- 第二次返回 `DEDUPLICATED`，不再创建新告警，`occurrence_count` 增加。

解释外部事件 ID 和 SHA-256 指纹的区别，以及 30 分钟去重窗口。

## 2:20 - 4:00 Incident 状态机

进入 P1 Incident：

- 展示两条关联告警和发生次数。
- 展示状态、处置人、指挥人和时间线。
- 执行“标记已缓解”。

说明每次更新带 `version`，多人同时处置时旧版本返回 409；状态机禁止跳过确认和调查直接关闭。

## 4:00 - 5:20 CMDB 与变更关联

打开“资源与拓扑”：

- 客户门户调用统一结算服务。
- 结算服务依赖 Redis 和 MySQL，并调用支付 API。
- Redis 当前降级。

回到资源台账，指出故障前 Redis 连接池上限从 200 调整为 120 的变更记录。

## 5:20 - 6:50 Agent 调查与实时事件

点击“运行 Agent 调查”，展开最新运行：

> 编排器先计划，再依次调用告警快照、CMDB 拓扑、指标快照、近期变更、日志检索和 Runbook 六个只读工具，然后根据证据覆盖重新规划并形成报告。每一步的状态、查询范围、Provider、证据数、耗时和失败原因都落库；这里展示的是实际执行轨迹，不是模型思维链。

运行过程中指向 `LIVE RUN` 面板，说明 `RUN_STARTED / STEP_STARTED / EVIDENCE_COLLECTED / RUN_COMPLETED` 先落库再通过 SSE 发出，事件 ID 可用于断线回放；关闭页面不会取消后台调查。完成后指出 `PLAN -> 6×EXECUTE -> REPLAN -> FINISH`、指标中的 P95/错误率/连接池 pending、日志中的相同 traceId 和脱敏后的 Token/IP。展开步骤输入，展示 `provider`、`query`、`windowStart/windowEnd`、`externalRef`。再打开 Provider 状态接口，说明 Prometheus/Loki 可按环境启用，失败时分别降级到本地指标/日志。单工具失败会标成 `PARTIAL`，且“时间相关性不是因果”，仍需回滚或指标对照验证。

## 6:50 - 7:50 受控处置审批

使用 `zhangwei` 发起调查后，在“受控处置提案”查看自动生成的高风险回滚草案：

- 展示目标资源、关联变更、Agent run 和证据引用。
- 指出发起人只能等待其他审批人复核，不能自批。
- 切换到 `lina`，填写审批依据并批准。
- 展示提案由 `PENDING_APPROVAL` 变为 `APPROVED`，版本从 1 增加到 2。
- 在时间线和审计日志中分别找到提案创建与审批记录。

强调“批准只解除治理门禁”：当前版本没有生产执行器，不会真的回滚 Redis 配置。该边界用于证明高风险动作受人控制，而不是伪造自动化执行能力。

## 7:50 - 8:50 OnCall 多轮对话

在当前 Incident 点击“继续对话”，询问“最可能的根因是什么？”：

- 展示回答中的告警、变更和调查报告引用。
- 追问“展示 Agent 调查过程”，说明助手读取的是同一个持久化 run，而不是重新编造步骤。
- 继续询问“下一步应该怎么验证？”，说明会话历史持久化且绑定同一 Incident。
- 指出 SSE 流式输出、Markdown 导出和用户会话隔离。
- 在右侧点击 Agent 调查“运行”，观察最新 run 更新，说明助手复用同一条持久化事件流，不会把 Agent 缩成一个装饰按钮。

强调调查报告是正式快照，对话用于持续协作；聊天不会自动执行生产操作。关闭 AI Key 后，证据规则回答仍然可用。

### V1.6 加演：Runbook 新旧与降级对照（建议替换一段 OnCall 追问，90 秒）

打开“处置手册”，先指向顶部四栏：原版 contains、BM25、Hybrid RRF 和语义索引。原版只有 3 条内置文本，依赖症状关键词 `contains`，没有版本、权限、分数或稳定引用。点击“运行评测”，在 13 条覆盖 Redis、接口延迟、认证、Kafka、MySQL 和 Kubernetes 的固定种子查询上展示：

- `LEGACY_CONTAINS_V1`：Recall@3 0%。
- `BM25_LOCAL_V1`：Recall@3 100%、MRR 96.15%、NDCG@3 97.16%、首位稳定引用命中 92.31%。
- `HYBRID_RRF_V1`：默认无 Key 时明确显示 unavailable，不复制 BM25 指标冒充向量效果。

检索“缓存客户端 active pending 慢命令连接排队”，展示首位结果 `Redis 连接池耗尽处置`、BM25 分数和 `runbook:legacy-runbook-2:v1#chunk-0`。依次切换 `BM25` 与 `HYBRID`：未启用向量时，页面显示“HYBRID 请求未执行；已降级 BM25”，并保留实际引擎和覆盖率，不让按钮名称伪装执行结果。再打开导入弹窗，指出 Markdown/PDF、5 MB/200 页边界、角色 ACL、相同内容幂等复用和内容变化生成新版本。

如果环境已配置真实 DashScope Embedding，用管理员账号点击“重建向量索引”：内容指纹不变时复用旧运行；成功后页面展示 provider/model、分块覆盖率和 RRF 双路名次。若没有 Key，就停留在默认降级演示，不填写或展示凭证。

在同一次检索中，对首位结果点击“高度相关”，对第二个确实有帮助的结果点击“部分相关”，页面分别记录等级 3/2 并等待独立复核。退出 `zhangwei`，改用 `admin` 或 `lina` 登录；待办只显示查询、当时返回的标题/摘要/稳定引用，不显示原始等级、评论或提交人。复核人根据快照分别选择自己的 0–3 级；选择 2/3 才生成正相关 qrel，选择 0/1 会保留双评分事实但不伪装成预期命中。顶部同步显示“双评样本数、精确一致率、线性加权 κ”；样本为空或标签无变化时明确显示 N/A。

通过两项后再次运行评测，页面显示 14 个查询、15 个 qrels，而不是错误地显示 15 个查询；NDCG@3 按复核后的最终等级计分。解释 κ 衡量两名标注人超出随机预期的一致性，线性权重让“差一级”和“差三级”承担不同惩罚；隔离演示的少量样本只证明计算与治理链路，不能宣称生产标注质量。

管理员继续查看“检索快照生命周期”：默认保留期为 30 天，页面分别显示活跃、到期、已擦除快照与累计脱敏字段数。演示库通常没有到期数据，不临时篡改时间或伪造清理效果；自动化验收会在隔离数据中回拨 `created_at`，证明到期快照按批次擦除查询正文、返回结果与查询人，到期但未复核的判断自动拒绝，已经独立复核形成的 qrel 仍可用于离线评测。重复清理返回 0 且不重复写审计，说明任务具备幂等性。

强调这不是生产准确率：13 条是种子集，演示中的第 14 条和 κ 都来自隔离环境样本；测试替身只验证索引、融合和失败契约，不能证明真实 Embedding 质量。真实历史样本、第三方仲裁、cross-encoder rerank、ANN、OCR、内容审核，以及数据库备份/导出副本的统一销毁仍是后续项。历史原型分支及 V1 内置 Runbook 均保留，可独立演示。

### V1.7 加演：从“恢复即结束”到防复发闭环（90 秒）

打开已恢复的 `INC-20260818-0002`，在“无责事故复盘”点击“从真实证据生成复盘草稿”。先指出页面如实显示 3 条固化时间线和 2 个证据引用：它们来自创建瞬间的真实 Incident 数据，且写库前脱敏；未恢复的事故不会提前生成根因结论。

补全根因和经验字段，创建一个“覆盖三个旧客户端版本的 token 刷新契约”行动项，选择张伟并设置截止日期。没有补完五类正文或没有行动项时，提交会被服务端拒绝。用李娜提交后，页面变为“待独立复核”并冻结编辑；李娜不能自审。切换 `admin`，填写依据后退回一次，修改后重新提交并发布。发布后正文只读，但张伟仍能把行动项标记完成，完成记录进入同一 Incident 时间线。

新旧效果对比：

| 阶段 | 原 OnCall/V1 | V1.6 | V1.7 checkpoint 01 | V1.7 checkpoint 02 |
| --- | --- | --- | --- | --- |
| 事故恢复后 | 对话结束，无正式复盘 | 可检索/评测 Runbook | 从真实证据生成无责复盘快照 | 按窗口统计 MTTA/MTTM/MTTR 并下钻慢事故 |
| 防复发治理 | 自然语言建议 | 相关性反馈形成 qrel | 负责人 + 截止日期 + 完成状态 | 跨事故待办、逾期天数、升级事实与关闭 |
| 质量门禁 | 无 | 检索判断独立复核 | 复盘提交人与发布人分离 | 独立分母、异常值排除、管理角色扫描 |
| 并发与追溯 | 无 | 判断版本与审计 | 父子乐观锁、时间线、审计、发布冻结 | 唯一升级记录、行锁、重复扫描幂等 |

历史原型、V1 内置 Runbook 和 V1.6 加演均保留。

### V1.7 加演：事故响应指标与行动项运营（75 秒）

打开“运营分析”，先说明日期窗口按 Incident 创建日期纳入，MTTA、MTTM、MTTR 各自显示均值、中位数和样本数。切换严重等级，指出未确认、未缓解或未恢复的事故只从对应指标分母排除，不能按 0 分钟美化结果；点击最慢事故编号回到 Incident 工作台核查时间线。

向下查看“防复发行动项”：先看“待接手”摘要，再组合“我的/全部”“开放/已完成”“负责人未确认/已确认”“仅逾期”筛选，展示事故、负责人、截止日期、逾期天数、外部回执与本人确认。用管理角色点击“扫描逾期”，第一次将逾期项标记为“已升级”，再次执行不会重复写时间线和审计；负责人确认不等于完成，完成后状态关闭但首次逾期事实保留。

checkpoint 39 草稿门禁加演：在隔离测试数据中先保留一份未发布且已过期的草稿行动项，执行逾期扫描，确认它不会产生升级、外部通知或完成记录；独立发布后再扫描，才形成一条升级并允许负责人完成。自动化测试另构造历史遗留草稿通知，证明派发前被阻断、发布后才可人工重试。本段是服务端 H2/MySQL 契约演示，不把默认页面种子数据或第三方渠道当作验证证据。原有已发布行动项演示继续保留。

最后主动说明页面上的升级只代表 OpsPilot 内部的持久化事实和审计，并不代表邮件、短信或企业 IM 已送达。跨事故语义相似/依赖共因聚类、Runbook 命中率趋势和真实外部渠道仍属于后续能力。

checkpoint 27 SLO 加演：继续向下查看“服务 SLO 与错误预算”。先对比上方 MTTR 表，说明它按 Incident 里程碑取样，不能当作可用性分母；新表从 Prometheus 分别读取好事件和总事件，并展示目标、滚动窗口、当前 SLI、允许坏事件与预算消耗。用李娜调整目标时携带版本号，旧页面重复提交会返回 409。关闭 Prometheus 后页面必须显示“未启用”而不是出现一组种子百分比；把 fixture 改成零分母或好事件大于总事件时分别显示“无数据/数据异常”，证明系统宁可拒算也不制造达标结果。历史 checkpoint-26 的服务级事故响应表继续保留，用于讲清两个指标体系的差别。

checkpoint 28 燃烧率加演：在 checkpoint-27 同一张 SLO 表中查看“多窗口燃烧率”。先展示 1h 已超 14.4x、但 5m 已恢复的场景，结果不 PAGE；再让 1h/5m 同时超阈值，页面变为“急速燃烧”。随后分别演示 6h/30m 的持续 PAGE 和 3d/6h 的工单级别，说明长窗口控制重大性、短窗口确认当前仍在燃烧。最后关闭 Prometheus，三档窗口值必须全部消失而不使用 Demo 数据；checkpoint-27 的累计 SLI、目标修改和异常分母 Demo 仍保留。

checkpoint 29 Alertmanager 加演：启动时配置 `ALERTMANAGER_WEBHOOK_SECRET`，用同一份 Alertmanager v4 payload 先后投递 firing、重复 firing 和 resolved。响应动作应依次为 `CREATED / REPLAYED / UPDATED`；再打开告警中心，显示 `alertmanager`、映射后的 P1、已恢复和 `×1`，证明重试与恢复都没有虚增 occurrence。切换状态筛选为 RESOLVED 后进入关联 Incident，时间线应只有一条“外部告警已恢复”。随后增加一条不存在的资源与有效 alert 同批投递，展示一条接受、一条 `REJECTED`，说明坏配置不会阻断好告警。历史私有 `/alerts/intake` Demo 保留，用于对比“内部单事件指纹压缩”和“外部生命周期幂等”的不同口径。

checkpoint 30 拒绝闭环加演：先向不存在的 `resource_code` 投递两次同一 firing，第二次仅改变 `endsAt`；两次响应应指向同一 rejection ID，告警页“待处理”中的投递数为 2、显示脱敏字段数且不显示 payload。修正上游资源标签后再投递，该项进入“已重放”并关联 Incident。再留一条未修复坏项，用 `lina` 展示受控“重放”按钮；切换 `auditor` 只能查看不能重放。这个 Demo 增补 checkpoint-29，不覆盖原 webhook 生命周期和历史私有 intake 对比。

### V1.7 加演：可解释重复事故与 Problem Management（90 秒）

打开“问题治理”，先指向顶部口径说明：候选只使用“同一归属服务 + 完全相同告警指纹”，并且必须跨至少两个不同 Incident。候选表将“独立 Incident 证据”和“告警 occurrence 总量”分列显示，解释同一事故内重复 100 次仍只算一次复发证据；页面不展示没有训练数据支撑的相似度百分比。

用管理员或李娜点击“登记 Problem”，指出当前窗口内匹配的历史 Incident 已被固化，重复点击和两名经理并发操作都只会复用同一 Problem。把状态切换为“已知错误”：不填写根因和临时规避方案时按钮保持不可提交，填写后保存，版本从 v0 变为 v1；再切换为“已解决”，补长期解决说明，版本变为 v2。说明这些门禁同时存在于服务端，普通值班员即使绕过页面也会得到 403。

随后接入一个新的同指纹 Incident。页面显示关联数增加和“解决后再次复发”，但 Problem 仍保持“已解决”；系统只自动收集事实，不静默替负责人重写治理结论。点击 Incident 编号可回到事故时间线查看 `PROBLEM_LINKED` 证据。

新旧效果对比：

| 能力 | 原 OnCall/V1 | V1.7 checkpoint 02 | V1.7 checkpoint 03 |
| --- | --- | --- | --- |
| 重复信号 | 对话中自然语言提及 | 可看事故指标和行动项 | 精确指纹跨 Incident 候选，分离事故数与告警噪声 |
| 长期问题 | 无持久化模型 | 每份复盘各自管理行动项 | 唯一 Problem、负责人、已知错误、规避方案和长期解决 |
| 自动关联 | 无 | 无跨事故关联 | 新同指纹 Incident 自动幂等关联并进入时间线 |
| 复发处理 | 无 | 人工查看统计 | 已解决后复发显式告警，状态不被后台静默重开 |
| 并发与追溯 | 无 | 行动项/升级事实可审计 | 双重唯一约束、乐观锁、权限、Incident 时间线和审计 |

历史原型、V1.6 与前两个 V1.7 加演均保留；这段可以按面试时间追加或替换，不覆盖旧演示证据。

checkpoint 04 边界加演：将服务名和告警标题分别设到 128 字、240 字，再登记候选。checkpoint 03 的自动拼接标题会超出数据库上限并返回 500；修复后摘要最多 240 字并显示省略号，源告警仍保留完整标题和指纹，两条 Incident 证据继续关联。此对照已由 API 自动化复现验证，作为原演示的补充。

checkpoint 05 工程加演：展示真实 MySQL 双事务测试。旧方案在唯一键冲突后执行普通回读，REPEATABLE READ 旧快照看不到赢家记录；第一版仅把主键查询改成加锁当前读，真实 CI 又揭示后续普通查询仍沿用旧快照；最终版用 `REQUIRES_NEW` 新事务覆盖完整冲突恢复。测试先让两个事务都确认候选未登记，再同时放行，最终应得到一创一复用、同一 Problem 主键、两条 Incident 关联和两条时间线。这个加演用于讲数据库隔离级别、失败驱动修复与幂等设计，不替代上面的产品页面演示。

checkpoint 21 Trace 加演：在受控环境启用 OTLP，展示一条告警接入 span 与一条 `HTTP -> agent.run -> agent.tool -> provider.query` 链路。展开 attribute 时只应看到 Alert/Incident/run/report ID、来源、工具/Provider 和状态；不应看到告警标题、描述、labels、PromQL/LogQL、Token 或 IP。如果没有可用 Collector，只展示自动化导出契约和父子 ID 断言，不用手工伪造截图；历史页面 Demo 继续保留。

checkpoint 22 Collector/Tempo 加演：保留上面的 SDK 契约演示，再执行：

```bash
OTEL_TRACING_ENABLED=true OTEL_TRACING_SAMPLING_PROBABILITY=1.0 \
docker compose --profile tracing up --build -d
```

打开 Grafana `http://localhost:3000`，进入 Explore 并选择已预置的 Tempo 数据源，使用 `{ resource.service.name = "opspilot" && span:name = "opspilot.agent.run" }` 查询。展开一次调查，应看到 1 个 run、6 个 tool、2 个 provider span，并可按 `opspilot.agent.run.id` 对应回 Incident 页面。CI 中的 `scripts/verify-tracing-pipeline.sh` 会用动态 run ID 做相同查询，经 Grafana 数据源代理读回，并检查隐私哨兵不在 trace JSON 中。

| 对比项 | checkpoint 21 | checkpoint 22 |
| --- | --- | --- |
| 导出证据 | 进程内真实 OTel SDK exporter | 应用经 Collector 导出到真实 Tempo |
| 查询方式 | 测试代码读取捕获列表 | TraceQL 搜索 + Grafana Tempo 数据源 |
| 层级断言 | SDK 层断言父子 ID | 存储后读回再断言 `1/6/2` 和 parent ID |
| 隐私检查 | 内存 span attribute 排除 payload | 对 Tempo 返回的完整 trace JSON 搜索动态哨兵 |
| 仍未覆盖 | Collector/UI | 跨服务传播、生产采样/对象存储、故障恢复 |

这是一段新增工程加演，不替换 Incident、OnCall 助手、Redis 故障或历史 UI 新旧截图。

checkpoint 23 故障恢复加演：执行同一个 `scripts/verify-tracing-pipeline.sh`，正常 trace 验证后脚本会停止 Tempo，再执行一次真实告警和六工具调查。演示时依次展示 `outage-app-health.json` 的 `UP`、`outage-collector.log` 的 `Exporting failed / connection refused` 和 `outage-result.json` 的恢复结果；不把简单停止/重启命令本身当作恢复证据。

| 对比项 | checkpoint 22 | checkpoint 23 |
| --- | --- | --- |
| Tempo | 全程可用 | Agent 调查期间明确停机 |
| 业务结果 | 正常调查完成 | 停机期间仍 `COMPLETED` 且应用 `UP` |
| 故障真实性 | 无故障 | Collector 在故障时间窗记录真实连接拒绝 |
| Trace 结果 | 立即可查 | Tempo 恢复后同一 run 最终可查且层级完整 |
| 声明边界 | 正常链路 | 仅 Collector 存活的有界后端故障，非持久化零丢失 |

checkpoint 21/22 的 SDK 与正常存储演示继续保留，checkpoint 23 是故障反例与恢复对照，不替换它们。

checkpoint 24 W3C 传播加演：运行 `HttpTracePropagationIntegrationTest`。测试启动两个真实本机 HTTP 端点模拟 Prometheus 与 Loki，在同一个 sampled root 下发起 Provider 查询；先展示修复前两个端点收到的 `traceparent` 都是 `null`，再展示改为注入 Spring Boot 自动配置的 `RestClient.Builder` 后，两个端点分别收到 `00-<root traceId>-<client spanId>-01`。最终断言导出数据中恰有两个 `opspilot.provider.query` 和两个 HTTP client span，每个 client 的 parent 都是对应 Provider span，header 的 spanId 与 client span 一一对应。

| 对比项 | checkpoint 23 及以前 | checkpoint 24 |
| --- | --- | --- |
| Provider HTTP 客户端 | 直接 `RestClient.builder()` | 注入 Boot 预配置的 prototype builder |
| 出站上下文 | Provider span 只在 OpsPilot 进程内 | Prometheus/Loki 请求携带 W3C `traceparent` |
| 自动化证据 | Tempo 内 `provider.query` 可查 | 两个真实 TCP 端点 + 导出 client span 双向核对 |
| 层级 | `tool -> provider.query` | `tool -> provider.query -> HTTP client` |
| 边界 | 单服务 Trace 与存储恢复 | 尚未证明真实下游 server span 或跨服务 Tempo 拼接 |

checkpoint 21/22/23 的 SDK、真实存储与故障恢复演示全部继续保留；本轮只补上出站传播缺口，不把模拟 HTTP 服务表述为生产 Prometheus/Loki 联调。

checkpoint 25 跨 JVM 加演：保留 checkpoint 24 的红/绿 header 对照，运行同一 `scripts/verify-tracing-pipeline.sh`。脚本会额外启动只在 `tracing-test` profile 存在的独立 Provider fixture，OpsPilot 的真实六工具调查通过 HTTP 调用它的 Prometheus/Loki 兼容端点。打开 Tempo 同一 run trace，应看到 `opspilot.provider.query -> OpsPilot HTTP CLIENT -> trace-provider-fixture HTTP SERVER` 两条分支；展示 `cross-service-spans.json` 中两个服务各自的 `service.name`、共享 traceId、CLIENT spanId 与 SERVER parentSpanId 逐一相等，再展示 Tempo 停机恢复后的 `outage-cross-service-spans.json`。不要只展示两个服务都“有 trace”，必须核对父子 ID。

| 对比项 | checkpoint 24 | checkpoint 25 已验收 |
| --- | --- | --- |
| 接收端 | 单进程测试里的本机 HTTP 端点 | 独立 JVM、独立容器、自动 SERVER span |
| 证据位置 | 进程内 exporter + 捕获的 `traceparent` | 两个服务导出到同一 Tempo trace |
| 层级 | `provider.query -> CLIENT` | `provider.query -> CLIENT -> SERVER`，严格 parent ID |
| 故障场景 | 无跨服务恢复断言 | Tempo 短暂停机后读回完整跨服务链路 |
| 边界 | 不代表真实下游 | 仍是协议 fixture，不代表生产 Prometheus/Loki 联调 |

本段是 checkpoint 21～24 之后的新增工程演示，原始 OnCall、Incident/OnCall 页面、Redis 和历史 Trace 演示继续保留。

## 8:50 - 9:20 值班与升级

打开“值班与升级”：

- 当前值班人由班次时间计算。
- P1 创建后立即通知值班人。
- 10 分钟未确认升级到经理，20 分钟升级到管理员角色。

说明当前 V1 记录通知，下一步可接企业微信和短信回执。

## 9:20 - 10:00 权限、审计和部署

打开“审计日志”，展示调查、提案创建、独立审批和状态流转记录。说明 `auditor` 可以看审计但执行流转或审批会返回 403。

最后展示：

```bash
./mvnw test
docker compose up --build -d
```

收束：

> 我保留并升级了原版 OnCall 多轮对话，但把它放进了 Incident、CMDB、责任人、状态机和审计组成的确定性闭环里。Agent 调查过程可实时恢复，高风险建议必须独立审批；AI 负责基于证据协作，不替代业务状态和风险控制。

## 常见演示故障

- 页面无数据：删除本地 `data/` 后重启，Flyway 会重新写入演示数据。
- 端口被占用：设置 `SERVER_PORT=9901`，或停止占用 9900 的进程。
- AI Key 缺失：保持 `AI_ENABLED=false`，规则调查仍可完整演示。
- Docker 首次启动慢：直接用 H2 本地模式演示，不让基础设施影响面试节奏。
