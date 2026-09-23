# OpsPilot 验收记录

本目录用于保存 OpsPilot 的阶段性验收报告。项目按“完成一组改动、形成一组证据、写入一份报告”的节奏持续迭代，报告中的结论只覆盖已经获得直接证据的范围。

| 版本 | 检查点 | 状态 | 报告 |
| --- | --- | --- | --- |
| V1.4 | 01：Agent 实时调查与受控处置实现 | 历史检查点，待验项已在 02/03 闭环 | [V1.4-checkpoint-01.md](V1.4-checkpoint-01.md) |
| V1.4 | 02：真实运行、响应式界面与助手闭环 | 通过 | [V1.4-checkpoint-02.md](V1.4-checkpoint-02.md) |
| V1.4 | 03：文档、全量回归、打包与重启 | 通过，V1.4 验收完成 | [V1.4-checkpoint-03.md](V1.4-checkpoint-03.md) |
| V1.5 | 01：运行控制与单实例交付质量 | 部分通过，运行控制主链路完成 | [V1.5-checkpoint-01.md](V1.5-checkpoint-01.md) |
| V1.5 | 02：真实 MySQL、CI 门禁与容器启动 | 通过，V1.5 本地验收完成 | [V1.5-checkpoint-02.md](V1.5-checkpoint-02.md) |
| V1.6 | 01：版本化 Runbook、BM25 与检索评测 | 部分通过，功能与 H2/UI 验收完成，MySQL V7 待复验 | [V1.6-checkpoint-01.md](V1.6-checkpoint-01.md) |
| V1.6 | 02：持久化向量索引、RRF 与可解释降级 | 部分通过，H2/测试替身/UI 已闭环，真实 Embedding 与 MySQL V8 待验 | [V1.6-checkpoint-02.md](V1.6-checkpoint-02.md) |
| V1.6 | 03：真实检索快照、人工标注与独立复核 | 部分通过，H2/API/UI 闭环完成，真实历史样本与 MySQL V9 待验 | [V1.6-checkpoint-03.md](V1.6-checkpoint-03.md) |
| V1.6 | 04：分级 qrels、唯一查询聚合与 NDCG@3 | 部分通过，H2/API/前端/JAR 闭环完成，真实历史样本与 MySQL V10 待验 | [V1.6-checkpoint-04.md](V1.6-checkpoint-04.md) |
| V1.6 | 05：盲化双评分与标注一致性 | 部分通过，真实历史样本待验；MySQL 已在 06 复验 | [V1.6-checkpoint-05.md](V1.6-checkpoint-05.md) |
| V1.6 | 06：检索快照脱敏、保留期与可审计清理 | 通过，主库生命周期、H2/MySQL、前端与 JAR 闭环完成 | [V1.6-checkpoint-06.md](V1.6-checkpoint-06.md) |
| V1.7 | 01：证据驱动无责复盘与防复发行动项 | 通过，H2/MySQL、权限并发、响应式前端与 JAR 闭环完成 | [V1.7-checkpoint-01.md](V1.7-checkpoint-01.md) |
| V1.7 | 02：事故响应指标与防复发行动项运营 | 通过，H2/MySQL、幂等升级、响应式分析页、JAR 与远端四段式 CI 已验收 | [V1.7-checkpoint-02.md](V1.7-checkpoint-02.md) |
| V1.7 | 03：可解释重复事故与 Problem Management | 通过，本地验收与远端四段式 CI 已闭环 | [V1.7-checkpoint-03.md](V1.7-checkpoint-03.md) |
| V1.7 | 04：长标题边界与证据保留 | 通过，41 项本地回归、JAR 与远端四段式 CI 已闭环 | [V1.7-checkpoint-04.md](V1.7-checkpoint-04.md) |
| V1.7 | 05：MySQL 快照可见性与并发提升 | 通过，失败证据、H2/JAR、真实 MySQL 双事务、前端与容器门禁闭环 | [V1.7-checkpoint-05.md](V1.7-checkpoint-05.md) |
| V1.7 | 21：OpenTelemetry 异步调查链路 | 通过，本地 85 项回归、前端/JAR 与远端六项 CI 已闭环 | [V1.7-checkpoint-21.md](V1.7-checkpoint-21.md) |
| V1.7 | 22：Collector、Tempo 与 Grafana Trace 闭环 | 通过，失败诊断、修复、TraceQL/Grafana 读回与远端七项 CI 闭环 | [V1.7-checkpoint-22.md](V1.7-checkpoint-22.md) |
| V1.7 | 23：Tempo 短暂故障与 Trace 恢复 | 通过，业务隔离、真实导出失败与有界 Trace 恢复闭环 | [V1.7-checkpoint-23.md](V1.7-checkpoint-23.md) |
| V1.7 | 24：Provider W3C Trace Context 传播 | 通过，本地协议/层级测试、86 项回归、JAR 与远端 Run 54 七项 CI 已闭环 | [V1.7-checkpoint-24.md](V1.7-checkpoint-24.md) |
| V1.7 | 25：跨 JVM CLIENT/SERVER Trace 拼接 | 通过，正常/故障恢复两轮严格父子关系、崩溃 outbox 租约修正与 Run 58 七项 CI 闭环 | [V1.7-checkpoint-25.md](V1.7-checkpoint-25.md) |
| V1.7 | 26：服务级 Incident 响应分析 | 通过，独立分母、响应式页面与 Run 60 七项 CI 闭环 | [V1.7-checkpoint-26.md](V1.7-checkpoint-26.md) |
| V1.7 | 27：真实 SLI 分母与服务错误预算 | 通过，本地全量、桌面/移动页、MySQL 8.4 与 Run 35613655790 七项 CI 闭环 | [V1.7-checkpoint-27.md](V1.7-checkpoint-27.md) |
| V1.7 | 28：多窗口错误预算燃烧率 | 通过，98 项后端发现/87 项执行、前端 13 项、JAR、启用/关闭实页与远端七项门禁通过 | [V1.7-checkpoint-28.md](V1.7-checkpoint-28.md) |
| V1.7 | 29：Alertmanager 标准接入与生命周期幂等 | 通过，本地 102 项后端、前端/JAR、真实 HTTP/实页与远端七项门禁闭环 | [V1.7-checkpoint-29.md](V1.7-checkpoint-29.md) |
| V1.7 | 30：Alertmanager 拒绝台账与受控重放 | 通过，本地 104 项后端、前端/JAR、真实 HTTP/响应式实页与远端七项 CI 已闭环 | [V1.7-checkpoint-30.md](V1.7-checkpoint-30.md) |
| V1.7 | 31：拒绝项自动重放与载荷保留期 | 通过，本地与远端七项 CI 已闭环 | [V1.7-checkpoint-31.md](V1.7-checkpoint-31.md) |
| V1.7 | 32：真实 Alertmanager 入站链路 | 通过，真实容器联调与远端八项 CI 已闭环 | [V1.7-checkpoint-32.md](V1.7-checkpoint-32.md) |
| V1.7 | 33：Prometheus 规则到 Incident 的真实链路 | 通过，远端九项 CI 与隔离规则联调已闭环 | [V1.7-checkpoint-33.md](V1.7-checkpoint-33.md) |
| V1.7 | 34：OpsPilot 自身服务指标到 Incident | 通过，真实 HTTP 指标规则与远端十项 CI 已闭环 | [V1.7-checkpoint-34.md](V1.7-checkpoint-34.md) |
| V1.7 | 35：逾期行动项外部提醒与投递回执 | 部分通过，本地契约与远端十项 CI 闭环；第三方渠道/浏览器待验 | [V1.7-checkpoint-35.md](V1.7-checkpoint-35.md) |
| V1.7 | 36：独立进程通知接收与重试联调 | 通过，真实容器联调与远端十一项 CI 闭环 | [V1.7-checkpoint-36.md](V1.7-checkpoint-36.md) |

## 状态约定

最新独立进程联调：[V1.7-checkpoint-36.md](V1.7-checkpoint-36.md)：单独 Node 接收容器、503→204 故障重试与重复扫描去重已由 [Run 35859792690](https://github.com/Trigger726/OnCall-Agent/actions/runs/35859792690) 真实容器实跑验证，十一项 CI 全绿。第三方渠道与页面浏览器验收仍待完成。

前一轮逾期提醒验收：[V1.7-checkpoint-35.md](V1.7-checkpoint-35.md)：默认关闭的通知 outbox、租约投递、端点 2xx 回执、重试与运营状态已完成本地全量回归；[Run 35858641814](https://github.com/Trigger726/OnCall-Agent/actions/runs/35858641814) 十项远端门禁全绿，含 MySQL 8.4 V22 入队快照断言。

最新服务指标验收：[V1.7-checkpoint-34.md](V1.7-checkpoint-34.md)：OpsPilot HTTP 401 计数器的 Prometheus 抓取、规则触发/恢复与 Alertmanager 生命周期已由 [Run 35855839535](https://github.com/Trigger726/OnCall-Agent/actions/runs/35855839535) 的真实容器联调验证，十项门禁全绿。生产阈值和指标端点隔离仍待完成。

最新规则链路验收：[V1.7-checkpoint-33.md](V1.7-checkpoint-33.md)：Prometheus 抓取可控测试指标、规则 firing/恢复、经 Alertmanager 到同一 OpsPilot Alert 的联调已由 [Run 35854884221](https://github.com/Trigger726/OnCall-Agent/actions/runs/35854884221) 实测通过；九项远端门禁全绿。生产指标源接入仍待验。

最新真实接入验收：[V1.7-checkpoint-32.md](V1.7-checkpoint-32.md)：固定版本 Alertmanager 的 webhook receiver、运行时专用密钥和 API v2 注入脚本已通过 [Run 35853706878](https://github.com/Trigger726/OnCall-Agent/actions/runs/35853706878) 的真实容器联调；八项远端门禁全绿。

最新拒绝项生命周期：[V1.7-checkpoint-31.md](V1.7-checkpoint-31.md)：默认关闭的 CMDB 缺失自动重放、有限指数退避抖动、到期载荷清理和原投递恢复。默认后端 111 项发现/99 项执行、前端 13 项、JAR、真实 HTTP 与桌面/390px 页面已通过；[Run 35852483053](https://github.com/Trigger726/OnCall-Agent/actions/runs/35852483053) 的 MySQL 8.4 与七项远端门禁全绿。

最新拒绝处置闭环：[V1.7-checkpoint-30.md](V1.7-checkpoint-30.md)：将 Alertmanager 永久坏项脱敏持久化，以稳定拒绝键归并重复投递，用短租约、原告警幂等和角色权限完成受控重放。本地后端 104 项发现/93 项执行、前端 13 项、生产构建、JAR、真实 HTTP 与桌面/390px 实页已通过；[Run 35762227045](https://github.com/Trigger726/OnCall-Agent/actions/runs/35762227045) 的 MySQL 8.4 与七项远端门禁全绿。

上一告警接入闭环：[V1.7-checkpoint-29.md](V1.7-checkpoint-29.md)：接收 Alertmanager v4 批量 webhook，用 fingerprint + startsAt 形成生命周期幂等键，同状态重试零写入、恢复更新同一告警，并隔离永久坏项。默认后端 102 项发现/91 项执行、前端 13 项、生产构建、JAR、真实页面，以及 [Run 35756244777](https://github.com/Trigger726/OnCall-Agent/actions/runs/35756244777) 七项远端门禁均通过。

最新燃烧率闭环：[V1.7-checkpoint-28.md](V1.7-checkpoint-28.md)：以 Google SRE 三档长/短窗口 AND 策略区分急速 PAGE、持续 PAGE、工单和稳定状态；异常分母拒算。默认后端 98 项发现/87 项执行、前端 13 项、生产构建、JAR、启用/关闭两种真实页面状态，以及 [Run 35741029914](https://github.com/Trigger726/OnCall-Agent/actions/runs/35741029914) 七项远端门禁均通过。

最新 SLO 分母闭环：[V1.7-checkpoint-27.md](V1.7-checkpoint-27.md)：服务目标和滚动窗口持久化，使用 Prometheus 好事件/总事件计算 SLI 与错误预算，对关闭、失败、零分母、多序列和矛盾数据显式拒算。默认后端 94 项发现/83 项执行通过，前端 13 项与真实桌面/移动页通过；[Run 35613655790](https://github.com/Trigger726/OnCall-Agent/actions/runs/35613655790) 的 MySQL 8.4 与七项门禁全绿。

最新服务级运营分析：[V1.7-checkpoint-26.md](V1.7-checkpoint-26.md)：同一窗口按 CMDB 归属服务拆分事故数、未关闭数和有效响应里程碑分母；不将其冒充可用性 SLO。本地 87 项后端、13 项前端测试及构建通过，[Run 60](https://github.com/Trigger726/OnCall-Agent/actions/runs/35419000535) 七项远端 CI 全绿。

最新跨 JVM 门禁：[V1.7-checkpoint-25.md](V1.7-checkpoint-25.md)：仅验收 profile 启动独立 instrumented Provider fixture，正常与 Tempo 短暂停机恢复后的两条 `CLIENT -> SERVER` 分支、严格父子 ID 与隐私门禁均通过；Run 58 七项 CI 全绿。

最新 W3C 出站传播：[V1.7-checkpoint-24.md](V1.7-checkpoint-24.md)：Prometheus/Loki 使用 Boot 管理的 `RestClient.Builder`，两个真实本机 HTTP 端点已验证 `traceparent`、client span 和父子层级，Run 54 七项 CI 全绿；真实下游 server span 与跨服务 Tempo 拼接仍待验。

最新 Trace 故障恢复：[V1.7-checkpoint-23.md](V1.7-checkpoint-23.md)：Tempo 停机期间调查仍完成且应用健康，Collector 真实观察到连接拒绝，后端恢复后同一 run 的 `1/6/2` span 与父子层级可查；不声明 Collector 重启后零丢失。

最新 Trace 存储与查询：[V1.7-checkpoint-22.md](V1.7-checkpoint-22.md)：可选 Collector/Tempo/Grafana profile 已通过真实 TraceQL/Grafana 读回、父子层级与敏感正文排除门禁；Run 50 远端七项 CI 全绿，失败与修复证据均已保留。

Trace 埋点基线：[V1.7-checkpoint-21.md](V1.7-checkpoint-21.md)：告警接入、异步 Agent run、六个工具与 Metrics/Logs Provider 形成可导出的 OpenTelemetry 父子链路；7 项定向自动化、85 项全量后端、13 项前端、真实 OTel SDK exporter 契约及远端六项 CI 已通过。

最新崩溃收敛：[V1.7-checkpoint-20.md](V1.7-checkpoint-20.md)：执行 JVM 退出后，存活实例按持久化 deadline 和行锁将孤儿 run 幂等结算为可回放终态。本地 78 项默认回归与 JAR、真实 MySQL 双协调者、MySQL/Redis 双 JVM 强制退出及远端六项 CI 均通过，原始结果已归档。

最新移动端闭环：[V1.7-checkpoint-19.md](V1.7-checkpoint-19.md)：OnCall 助手在手机/平板提供 Incident 与 Agent 调查抽屉；严格回归同时修复取消后原 POST SSE 未呈现终态的问题，两个页面改为对同一 run GET 回放。13 项前端测试、构建、真实 Edge 新旧对照及远端六项 CI 通过。

最新页面恢复：[V1.7-checkpoint-18.md](V1.7-checkpoint-18.md)：Incident／OnCall 助手刷新后自动 GET 挂接活动调查、保留会话 URL、旧订阅清理；13 项前端测试、真实浏览器新旧对照及六项 CI 通过，证据已归档。

最新接收器修复：[V1.7-checkpoint-17.md](V1.7-checkpoint-17.md)：Stream 重建后回退/复用 ID 的传输游标恢复；修复前后对照、真实 Redis 100/100/5 有界恢复、双 JVM 回归及六项 CI 通过，证据已归档。

最新故障验证：[V1.7-checkpoint-16.md](V1.7-checkpoint-16.md)：真实 Redis 暂停期间双实例各完整收到 20 条事件，恢复后 outbox 从 18 条自动排空；正常/故障双用例及六项 CI 通过，原始结果已归档。

最新双实例门禁：[V1.7-checkpoint-15.md](V1.7-checkpoint-15.md)：两个独立 JVM 共享真实 MySQL/Redis，通过 HTTP 验证广播与游标续订，六项 CI 通过；保留初版及排除首次补读干扰后的结果。

最新前端恢复：[V1.7-checkpoint-14.md](V1.7-checkpoint-14.md)：自动 GET 续订、去重、退出中止与新旧真实页面对照，本地及远端五项 CI 通过。

最新订阅实现：[V1.7-checkpoint-13.md](V1.7-checkpoint-13.md)：GET SSE、有界本地订阅与数据库补读，63 项默认回归和五项 CI 通过；前端自动恢复及双 Web 实例尚未验收。

最新接收端实现：[V1.7-checkpoint-12.md](V1.7-checkpoint-12.md)：实例独立 XREAD 游标与失败重试，真实 Redis 和五项 CI 通过；尚未接入 SSE 订阅。

最新保留期实现：[V1.7-checkpoint-11.md](V1.7-checkpoint-11.md)：有限批次清理已投递 outbox、Redis 通知裁剪并保留原始回放，五项 CI 已通过。

最新发送端实现：[V1.7-checkpoint-10.md](V1.7-checkpoint-10.md)：Redis Streams relay、失败退避和真实 Redis 验证通过，五项 CI 全绿；跨实例接收端尚未实现。

最新实现：[V1.7-checkpoint-09.md](V1.7-checkpoint-09.md)：V16/V17 同事务 outbox 与租约领取，H2/MySQL 与四段式 CI 通过，Redis relay 尚未接入。

下一阶段设计审计：[ADR-001](../ADR-001-distributed-agent-events.md) 记录原版、本地 SSE 与跨实例目标的差异及故障验收矩阵。outbox、Redis 发送/接收、GET 订阅和调用内前端恢复已分阶段实现；双 JVM 正常/Redis 暂停场景已验收，完整故障矩阵与双实例浏览器演示仍待验，不能将局部检查点等同于完整分布式验收。

最新故障隔离补强：[V1.7-checkpoint-08.md](V1.7-checkpoint-08.md)：推送异常不影响已提交事件与完整调查，4 项集成测试及远端四段式 CI 通过。

最新事件一致性补强：[V1.7-checkpoint-07.md](V1.7-checkpoint-07.md)：提交后推送、回滚不推送，定向集成测试及远端四段式 CI 通过。

最新执行器补强：[V1.7-checkpoint-06.md](V1.7-checkpoint-06.md)：排队取消后立即回收容量，定向 3 项回归与远端四段式门禁通过。

最新补强：[V1.7-checkpoint-05.md](V1.7-checkpoint-05.md) 将上一检查点识别的 MySQL 并发证据缺口落实为确定性双事务门禁。

- `通过`：该检查点的功能、测试、运行和文档证据均已闭环。
- `部分通过`：实现和部分验证已经完成，但仍有明确待验项。
- `不通过`：发现阻断交付的问题，需要修复后重新验收。
