# 原始 OnCall 与封版能力对照

2026-10-11。结论：OpsPilot 已增强 Incident 业务闭环和运行控制，但不是原版所有接口、工具和检索实现的兼容替代。本文按源码与已有证据区分“能力重写”“已增强”“未保留”“未验证”，不把模型回答看起来相似当作等价。

## 2026-10-15 后续增量说明

下方对照表与来源哈希保留的是 2026-10-11 封版审计时点，不应再当成“最新助手没有问题驱动检索”的证明。之后 [CP01](acceptance/V1.8-checkpoint-01-assistant-runbook.md) 已接入逐问题 Runbook 检索，[CP02/03](acceptance/V1.8-checkpoint-03-assistant-observations.md) 增加引用/取证边界，[CP04/05](acceptance/V1.8-checkpoint-05-scoped-evaluation.md) 加入 Incident 范围与可追溯评测。本轮 [CP07](acceptance/V1.8-checkpoint-07-document-diversity.md) 又用同语料固定 32 题、真实 H2/MySQL 和整包 SSE 验证了手册级结果预算。最近身份说明由系统直答，不无谓检索故障资料。

这些是原“部分迁移”缺口上的实际增量，不证明原版 Milvus/gte-rerank 已等价迁移或质量被超越，也不恢复任意模型工具调用、天气、TXT/旧 API 等未保留功能。原版固定归档和历史 Demo 继续保留。用户新目标中的冗余功能删除，需要结合实际使用与当前行为判断，不能仅因旧表列为“未保留”就认定它无用。

后续 [CP08](acceptance/V1.8-checkpoint-08-citation-source.md)补齐了用户可操作的引用追溯：助手证据打开精确持久化版本/章节，同一查询限制发布状态与当前角色 ACL，旧版本不跳转最新版。新旧整包保存回答字节相同，163 Java/266 前端/292 Node 与来源实页流程通过；不是新的检索准确率、真实 MySQL、云模型或原 Milvus 等价结论。下方封版表仍保留原时点，不回写历史判断。

## 审计对象与证据等级（2026-10-11）

后续 [CP10](acceptance/V1.8-checkpoint-10-answer-reader.md)依据原 OnCall `renderMarkdown/highlightCodeBlocks` 的源码能力，补齐当前回答中安全代码围栏/行内代码与原始步骤显示，可信正文引用复用精确来源读取；不是任意 HTML、完整 Markdown 或旧高亮库的等价移植。实页另修复新导入 fenced 注释造成的章节截断；旧持久内容不重写。本机与独立 Linux CI 原附件、同保存回答新旧 Demo 对比已核验，未推导检索准确率或模型能力提升。以下历史表仍按封版时点阅读。

- 原版：本机/远端 `archive/oncall-original-2026-08-19` 均为 `0cd654ea49957d05c0e5c683c0e945a5a87c5bab`，代码位于 `SuperBizAgent-release-2026-05-17`。只读 Git 对象，不检出、不启动需要外部凭证的旧应用。
- 当前：交付分支在审计时为 `23f534c0135240e29aa80bf82ac2fba9713aa87f`；封版业务 tag 仍为 `bb012bf`。本轮只写文档和来源清单，不改代码、不重新执行测试。
- **源码存在**仅说明接口/接线已实现；**已有验收**仅覆盖链接报告的实际场景，不扩展为本轮重跑或生产证明。**未保留**指当前源码没有对应接线/兼容接口；不推断原版功能无价值，也不否定可从归档单独演示。
- [Git 来源与当前文件一致性清单](assets/v1.7-baseline-audit/source-proof.json)记录固定提交、原始 blob、当前 blob 与物理文件核对。不是行为回归或检索质量测试。

## 原版能力逐项对照（封版时点）

原版主接口可在[固定 ChatController](https://github.com/Trigger726/OnCall-Agent/blob/0cd654ea49957d05c0e5c683c0e945a5a87c5bab/SuperBizAgent-release-2026-05-17/src/main/java/org/example/controller/ChatController.java)查看；其他原文件的具体路径/blob 见来源清单。

| 原版能力 / 权威源码 | 当前实现 / 权威源码 | 判断与限制 |
| --- | --- | --- |
| 普通聊天 `/api/chat`、6 对消息窗口、会话列表/详情/清空/导出；ChatController | [AssistantController](../src/main/java/org/trigger/opspilot/assistant/AssistantController.java)、[AssistantService](../src/main/java/org/trigger/opspilot/assistant/AssistantService.java) | **能力重写并增强**：SQL 持久化、owner 约束、当前授权/事务围栏；窗口和响应格式不是原版兼容承诺 |
| `/api/chat_stream` 模型增量 SSE；ChatController/ChatService | [AssistantAiService](../src/main/java/org/trigger/opspilot/assistant/AssistantAiService.java)、[流入口](../src/main/java/org/trigger/opspilot/assistant/AssistantController.java) | **已实现原生片段，但接口重写**：[CP75](acceptance/V1.7-checkpoint-75.md)、[双 JVM CP84](acceptance/V1.7-checkpoint-84.md)为已有限定行为证据；本轮未调用真实 DashScope |
| ReactAgent 自动选用内部文档、指标、日志等工具；ChatService.buildMethodToolsArray/createReactAgent | [AssistantAiService](../src/main/java/org/trigger/opspilot/assistant/AssistantAiService.java) + [Incident 上下文](../src/main/java/org/trigger/opspilot/assistant/AssistantService.java) | **不等价**：当前模型只消费已收集上下文，原生流关闭内部工具执行、拒绝模型 tool calls；不能声称保留原版任意问题的自动工具调用 |
| 内部文档问答与直接搜索 `/api/search-docs`；InternalDocsTools/RagService | [独立 Runbook 检索](../src/main/java/org/trigger/opspilot/runbook/RunbookController.java)、[调查工具](../src/main/java/org/trigger/opspilot/investigation/tool/RunbookSearchTool.java) | **部分能力迁移**：独立搜索/调查引用存在；助手上下文没有按每个任意问题自动检索知识库。独立 Runbook 页面不是原聊天 RAG 的等价证据 |
| Milvus L2 向量召回 + 可选 `gte-rerank`；VectorSearchService/InternalDocsTools/RerankService | [语义索引](../src/main/java/org/trigger/opspilot/runbook/RunbookSemanticIndexService.java)、[RRF](../src/main/java/org/trigger/opspilot/runbook/ReciprocalRankFusion.java)、[检索服务](../src/main/java/org/trigger/opspilot/runbook/RunbookService.java) | **实现替换，非质量等价**：SQL 版本化向量 + BM25/可选语义召回 + RRF；RRF 不是神经 rerank。原 `gte-rerank` 接线未保留；[V1.6 原审计](acceptance/V1.6-checkpoint-02.md)不能证明新方案优于原版 |
| `/api/upload` 允许 `txt,md`，同名文件覆盖并尝试自动索引；FileUploadController/原配置 | [RunbookService.importFile](../src/main/java/org/trigger/opspilot/runbook/RunbookService.java) | **收紧格式并增强发布约束**：当前仅 `.md/.markdown/.pdf`，拒绝 `.txt`；不可变版本/发布/ACL 不是旧上传接口兼容。尚无全部旧语料逐条迁移验收 |
| Planner/Executor/Supervisor 模型协作与报告；AiOpsService、`/api/ai_ops` | [InvestigationService](../src/main/java/org/trigger/opspilot/investigation/InvestigationService.java) | **受控业务重写**：固定工具执行、规则结论、记录 PLAN/REPLAN/FINISH，可选 AI 润色；阶段名称相似不证明沿用原模型驱动 Supervisor/Replanner |
| 指标/日志工具；QueryMetricsTools/QueryLogsTools | [指标工具](../src/main/java/org/trigger/opspilot/investigation/tool/MetricsSnapshotTool.java)、[日志工具](../src/main/java/org/trigger/opspilot/investigation/tool/LogSearchTool.java) | **业务化增强**：同源只读证据/Provider/失败原因落库。原环境、区域参数、所有 MCP 工具协议没有全量兼容验收 |
| `queryWeather` 实时天气、`getCurrentDateTime` 工具；WeatherTools/DateTimeTools，并注册到 ChatService | 当前助手/调查源码中无相应工具接线 | **未保留**；模型凭知识回答时间/天气不等于调用实时工具。归档仍保留原工具源码 |
| 告警手册全量/等级/类别/搜索/详情/统计/reload；AlertHandbookController、alert-handbook.json | 业务告警与 Runbook 是独立模型 | **未复刻原目录 API/页面**；不能用 Incident 告警列表替代静态手册目录，也未验证所有旧规则迁移 |
| `/api/health`、`/milvus/health`；ChatController/MilvusCheckController | Actuator 管理端口 + Runbook 语义索引状态 | **实现替换**：当前不使用 Milvus；旧健康检查 API 无兼容保证 |
| 原 static/index.html/app.js/styles.css | [Vue 控制台](../web/src/layouts/MainLayout.vue)、[已验收新旧图](acceptance/V1.7-ui-claude-design.md#截图) | **UI 重构**：原 3 个静态文件及归档分支仍存在。当前 4 份本机 Demo 摘要保留；本轮没有重新跑原版 UI/模型 A/B |

接口兼容的总边界：当前采用 `/api/v1`、JWT/角色和业务资源 ID；未提供旧 `/api/chat*`、`/api/ai_ops`、`/api/search-docs`、`/api/upload` 的兼容层。不得让原客户端直接换 URL 后声称“无缝升级”。

## 成熟 Incident/AIOps 的对照，而非功能平齐声明

参考的是官方工作流，不把产品宣传或我们的实现意图当作验收。下表判断是基于官方来源与当前源码/报告的**审计推断**，不是与商业产品的全功能/生产性能比较。

| 参考工作流 | OpsPilot 对应证据 | 仍不能声称 |
| --- | --- | --- |
| [PagerDuty 的确认、升级与解决分离](https://support.pagerduty.com/main/docs/incidents)、[有序升级策略](https://support.pagerduty.com/main/docs/escalation-policies) | [Incident 状态机](../src/main/java/org/trigger/opspilot/incident/IncidentController.java)、[业务封版证据](acceptance/V1.7-checkpoint-124.md#cp124自身远端闭合) | 通知 HTTP 2xx=人已读/确认；所有渠道、覆盖缺口和自动解决策略与 PagerDuty 一致 |
| [Grafana IRM 的告警分组、排班、升级、渠道通知、响应](https://grafana.com/docs/grafana-cloud/observe-and-act/respond-to-incidents/introduction/) | 排班/临时覆盖/轮转、接班/换班/开放认领和通知 outbox 已分阶段实现；[实际通知/重启/人的动作](acceptance/V1.7-checkpoint-124.md)分别记录 | 已具备短信/电话/移动推送真实账号、第三方本人已读、完整跨时区/DST、日历与跨机器 HA |
| [Google SRE 的无责复盘、正式复核、行动项与知识分享](https://sre.google/sre-book/postmortem-culture/) | [PostmortemController](../src/main/java/org/trigger/opspilot/postmortem/PostmortemController.java)、[现有架构](ARCHITECTURE.md)有恢复后草稿、独立发布、责任/完成/逾期事实 | 已具备实时多人协作文档、商业协作工具集成或团队实际复盘成效 |

版本时效提醒：Grafana OnCall OSS 已在 **2026-03-24** 归档，当前活跃产品是 Grafana Cloud IRM；不能在新的方案描述中将 OSS 写成仍活跃维护的依赖。[Grafana 官方归档说明](https://grafana.com/docs/oncall/latest/set-up/open-source/)

## 面试表达与封版处理

可以讲：“从原 OnCall 原型重构为证据驱动 Incident 平台；增强持久化、幂等/取消/预算/饱和反馈、当前授权、独立审批与可审计通知，保留原分支和历史 Demo。”

不应讲：“原版功能全部无损保留”“多 Agent 架构完全等价”“RRF 就是 rerank”“检索一定更准确”“通知已被人收到”“商业 Incident 平台能力全覆盖”。

本轮完成的是来源固定和差异识别，不是消除差异。真实检索/聊天同语料同问题 A/B、原协议兼容、自动 RAG 工具调用、reranker/TXT/手册/天气等是否回补，都涉及重新开启产品范围；遵守[封版约束](releases/V1.7-SEALED.md)，不自动实现。正式恢复业务迭代须先明确具体需求与验收口径，不继续用 UI/文档检查代替这些缺口的完成证明。
