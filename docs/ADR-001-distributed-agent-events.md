# ADR-001：Agent 跨实例事件分发与恢复

日期：2026-09-08。状态：设计决策，尚未实现/验收。适用基线：`e600cec`。

## 当前证据

- 原版备份 `../项目备份/OnCall-Agent-original-2026-08-19/src/main/java/org/example/controller/ChatController.java` 使用本地 ConcurrentHashMap 会话与 cached thread pool。保留该基线，不修改历史备份。
- `InvestigationController` 只向发起 POST 的 emitter 推送；复用 run 时回放现有事件后关闭连接。
- `AgentRunEventController` 的 GET events 返回 JSON 列表，没有跨实例实时订阅端点。
- `web/src/services/agentStream.ts` 只消费一次 POST fetch stream，没有持久游标、事件去重、自动回放或重新订阅；页面 finally 刷新详情。已有“支持回放”是服务端能力，不能等同于前端已自动恢复。
- 事件表以 `(run_id, sequence_no)` 唯一，分配序号时锁住对应 run；不同 run 可以并发提交。afterCommit 与故障隔离已完成，但提交后进程退出可能漏发通知。

## 参考与取舍

[Robusta Playbooks](https://docs.robusta.dev/master/playbook-reference/index.html) 将触发、动作执行与通知 sinks 分开。本项目借鉴职责分离，不能据此宣称采用 Robusta 的内部消息可靠性机制。

[Redis XREAD](https://redis.io/docs/latest/commands/xread/) 允许各读取者独立按游标读取；[XREADGROUP](https://redis.io/docs/latest/commands/xreadgroup/) 将组内消息分配给消费者。所有 Web 实例共用一个消费组会使部分持有 SSE 连接的实例收不到通知，因此采用每实例独立 XREAD 游标。Streams 是跨实例唤醒通道，业务回放仍读数据库。

[Debezium Outbox Event Router](https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html) 使用唯一事件 ID 识别重复、aggregate ID 路由相关事件。本项目采用同事务 outbox 思路，初期用数据库领取任务与 Redis Streams 投递，不引入 Kafka Connect/CDC 运维组件。

## 决策

1. 保留 H2 无 Redis 本地演示。显式配置 Redis 模式才启动分发；共享 MySQL 的两实例作为分布式验收环境。
2. 事件插入和 outbox 待投递记录在同一事务提交。outbox 引用 event ID，不重复复制证据正文。开启分发后新事件必须可恢复投递；旧事件通过原回放接口保留，不默认广播全部历史数据。
3. 以 PENDING/租约到期记录领取批次，数据库短事务完成领取；网络 XADD 在事务外执行。成功后用租约 token 条件更新完成状态；失败记录下次尝试时间，有限指数退避。不能用全局 `id > lastPublishedId` 扫描：不同 run 的提交顺序可能不同，较小 ID 晚提交会被永久跳过。
4. XADD 使用 Redis 自身生成的 Stream ID；字段只携带 schemaVersion、runId、eventId。eventId 是幂等标识，Redis ID 只作传输游标。发送成功但标记前崩溃允许重复；不承诺 exactly-once。
5. 各实例独立 XREAD，收到通知后按本地订阅 run 从数据库读取已提交事件。通知乱序或重复不直接改变浏览器游标，发送始终按数据库事件序列推进。数据库负责正文和权限，Redis 通知不得注入正文。
6. 新增 GET run 实时订阅，先登记订阅再补读数据库；登记、补读与到达通知的交错须串行合并并去重。沿用数据库事件 ID 作为 SSE id，JSON 回放接口继续兼容。
7. 前端保存当前 runId 和最后已处理 eventId，初始 POST 断开/幂等复用结束但 run 未终态时转 GET 订阅；重连有退避和上限。终态停止，页面退出 AbortSignal 只断开订阅，显式取消继续走取消 API。重复 eventId 不重复渲染。
8. Redis 中断、Stream 裁剪或重启时，通过低频数据库补读使活跃订阅继续推进；新增订阅总是从客户端数据库游标恢复。慢客户端使用有界缓冲并断开，不能阻塞 run 执行。Stream 长度与 outbox 已投递记录有明确保留期，待投递记录不能为控制容量而静默删除。

## 必须通过的验收矩阵

| 场景 | 验收证据 |
| --- | --- |
| 原版/单实例/新两实例 | 保留历史 Demo；A 发起调查、B 建立订阅，B 收到同一 run 终态且事件集与数据库一致 |
| 外层回滚 | 事件与 outbox 均不留行、不出现 Redis 通知 |
| 提交后 relay 尚未运行即重启 | 重启 relay 后补发全部未投递事件 |
| XADD 成功后标记前退出 | 重发允许，前端每个 eventId 只呈现一次 |
| 两 relay 同时领取、租约过期 | 旧租约不能覆盖新领取者状态；无永久遗失 |
| 较小 eventId 晚提交 | 仍能被领取和分发，证明未用全局高水位漏扫 |
| 订阅注册与历史补读间到达新事件 | 历史与实时合并无缺口、无重复 |
| 通知乱序/重复 | 浏览器处理顺序与数据库一致，不跳过较早事件 |
| Redis 断开/重启/裁剪 | 调查不失败；订阅通过数据库恢复；恢复后停止过度补读 |
| 慢客户端/批量关闭订阅 | 执行器不被发送阻塞；缓冲、连接和任务数有上限并释放 |
| 无权限/不存在 run | 订阅和回放遵守统一权限，不泄露通知正文 |
| 终态、页面退出、显式取消 | 自动重连停止；取消语义与本地模式一致 |

## 实现次序与完成口径

下一步先实现同事务 outbox、领取/租约/重试以及真实 MySQL 并发测试；随后接入真实 Redis Testcontainers 与两个独立订阅实例；最后连接 GET SSE、前端游标恢复、双实例浏览器演示和独立 CI 门禁。只写 ADR、表结构或模拟 broker 通过都不能标记分布式交付完成。

事件分发不等于执行任务可迁移：AgentExecutionManager 的任务和 deadline 仍在本地。运行实例崩溃后的任务恢复/终态结算是独立缺口，必须另行验证，不能因为 B 能回放 A 的事件就声称 run 会在 B 自动继续。
