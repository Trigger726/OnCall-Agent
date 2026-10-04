# OpsPilot 架构设计

最新 CP93：[持久化时间边界夹具/门禁修复](acceptance/V1.7-checkpoint-93.md)已由自身033f491/[Run37199872957](https://github.com/Trigger726/OnCall-Agent/actions/runs/37199872957)十八CI success闭合，见[五ZIP自身proof](assets/v1.7-cp93/remote-proof.json)。真实MySQL19零跳过及两次.900→实际下一整秒/DB释放超过存储截止，独立强化门禁重放与CI保存结果相等；原17/27/1/99、双时区各480执行/171跳过/83新鲜XML、前端132/audit0及Linux原12/14/8/6与HTTP三流程也核对。脚本115为本地结果。生产代码/DDL/页面未改，没有新视觉Demo；CP92自己的[首次失败](assets/v1.7-cp92/first-remote-proof.json)保留，不改写绿灯。专用撤销页面、冻结原键/三个版本、真实浏览器丢响应/409/身份围栏和桌面手机新旧对照继续必需，完整路线/目标active。

最新保留页面边界见[CP90](acceptance/V1.7-checkpoint-90.md)：以数据库快照/完整期限精度显示事实，首次未冻结人工POST前重新GET并锁原版本；事实未知/到期拒绝新提交，已冻结原version/reason仍只显式求原回执。新12实页/前端132本地通过，自己的CI待验。后台V36/期限领取/清理及原回执围栏的CP89自身十八CI、真实MySQL通知27已由[89第六节](acceptance/V1.7-checkpoint-89.md)闭环；真实MySQL旧V35有数据升级尚未验证。技术投递、人工决定、覆盖责任三层不混同，历史Demo及以下阶段记录保留。

当前CP88已闭环：源码7558b9d的[Run37172618761](https://github.com/Trigger726/OnCall-Agent/actions/runs/37172618761)十八作业全部success，见[88第六节](acceptance/V1.7-checkpoint-88.md)/[自身远端proof](assets/v1.7-cp88/remote-proof.json)。九个选定ZIP源HEAD/官方摘要/实际字节及原门禁独立核验；真实MySQL通知18与原换班17零跳过、原生18/兼容99/双JVM六项通过，双时区各451执行/142条件跳过、158新鲜XML，前端129/audit0，Linux新九流程及原14/8/6实页通过，三本轮截图已目视。仅修共用测试的原生CHECK精确断言，生产代码、页面和门禁未改，不制造CP88视觉变化。87首次MySQL失败及旧Demo完整保留；以下按各历史阶段当时范围阅读，不把旧“待验”当本轮终态。

## CP87换班通知与独立技术投递事实

V35 outbox与请求/决定/双覆盖/审计同事务，事件版本+收件人唯一，出站仅冻结历史窗口/参与者与专用稳定键；HTTP不在业务事务。独立单worker/SynchronousQueue无tick积压，数据库当前时间逐条条件lease、token+有效期CAS旧回执围栏、有限退避与失败原版本人工重试。资格/事件失效跳过、禁止重定向转凭证、不保留响应正文；默认关闭不回填。人工重试审计同事务、当前DB角色与实际参与者检查，旧意图回执不重新入队或改业务决定。

子面板只查通知facts、按账号sessionStorage首次发送前冻结重试版本/说明；存储失败不POST，失效409跨刷新锁定，晚200身份epoch不修改替换账号，读取失败清旧回执。技术DELIVERED、人确认、当前coverage三种事实分开，不保证远端exactly-once或配对coverage同快照。[本地九实页/18场景/双时区证据与尚待MySQL](acceptance/V1.7-checkpoint-87.md)。其余保留/清理策略及整体路线未完成。

## CP86双向换班真实双方页面

班次维护新增本人未来普通班次入口，独立SwapPanel先显示两个责任窗口、查询对方班次，再申请/指定对方一次决定。共享API仍85/V34同一SQL事务；单向接班/轮转/覆盖与新面板互相刷新，未替换旧记录。申请冻结原UUID/双源版本/快照，决定冻结原请求版本/说明；首次POST前保存按账号版本化sessionStorage，损坏/写失败不POST，响应不明只手动原载荷重试，403/409锁定跨刷新保留，明确放弃不撤回服务器请求或复活覆盖。

账户事件和组件退出增加epoch，异步响应还校验原账号/令牌；同文档SPA换账号之后确实到达的旧200不能清旧账号草稿或污染新显示。详情重新GET原申请和两次coverage，历史ACCEPTED不等同当前覆盖；明确两个区间不是联合原子快照，读取失败清旧事实。未提供成对撤销/通知/开放认领/部分或已开始交换/DST。最终十四/八/六实页、七换班流程、120前端/102Node与旧页桌面手机对照已本地验，[86报告](acceptance/V1.7-checkpoint-86.md)。85新MySQL17已真实限定通过，但该源H2的MockMvc并发打印器错误使整轮失败；86仅关该测试自动打印、保留原断言/补失败工件，86自身4a4141c的十八CI及九ZIP独立核验现已闭环，详见[86第六节](acceptance/V1.7-checkpoint-86.md)；不外推剩余通知/DST或生产容量。

## CP85参与者确认的双向原子换班

V34独立换班台账冻结两个普通源班次、双方、时段及版本。按计划ID/用户ID规范顺序加行锁，接受时重查双方最新资格、两源/计划/开始时间/重叠，复用原roster.create在同一READ_COMMITTED事务内生成两条覆盖、决定及审计。第二覆盖或最终审计失败全部回滚；本人申请、指定对方确认，ADMIN不代替参与者。原班次和轮转不变，重复原键/决定仅回执，不复活已取消覆盖。GET历史ACCEPTED不是当前责任，coverage为准；拒绝/撤回可结束源已失效的待办。

85首次本地验收：同/跨计划、回滚、反向竞争、台账截断和HTTP权限等17共享H2场景、双时区各433实际执行/100Node，以及旧404→同库V34新包真实HTTP对照通过。随后85自身源CI的真实MySQL17项限定通过，但整轮因H2测试打印器并发错误失败，原始证据保留在[85第五节](acceptance/V1.7-checkpoint-85.md)。后端契约见[ONCALL_SWAPS](ONCALL_SWAPS.md)。页面已在86补齐，本地渲染与自身4a4141c十八CI/九ZIP远端核验均通过；通知/开放认领/已开始部分时段/成对撤销仍未完成，不宣称完整产品。84自身双JVM六项MySQL/十七CI与六ZIP独立重放已闭环，见[84第四节](acceptance/V1.7-checkpoint-84.md)，不替代此轮新事务。

## CP78停止后的输出容量自然回收

默认真实TCP门禁保留77四项，新增第三自有JVM的1 writer/0队列场景。取消先关闭模型HTTP、原worker完成新同步问题；唯一输出仍卡Tomcat写时新流503问题0/模型0。完全不RESUME/不关旧客户端、不放行Provider，80秒monotonic界限内要求同名writer退出Transport.run并回到ThreadPoolExecutor.getTask，再由新原生流完整提交唯一答案。Windows同44e包三次约60秒；119cee1自身Linux五项通过，首次池空闲60.224秒、新答案60.424秒，采样不是精确socket-close时间或所有连接TTL。该源整轮CI因轮转浏览器失败，见[78报告](acceptance/V1.7-checkpoint-78.md)。生产socket/timeout/线程池默认未改。

## CP77慢消费者资源隔离（本地与自身远端限定通过）

原包在暂停TCP且输出未超既有上限时，使模型worker持有transport锁阻塞Tomcat写出，SQL已CANCELLED但取消HTTP/模型HTTP/worker尚未释放；[反例](assets/v1.7-cp77/baseline-proof.json)保留。新AssistantStreamTransport只把阻塞写入移到默认4 writer/16待执行transport，每个transport一槽交接；容量先于业务接纳预留，满时503/ASSISTANT_STREAM_SATURATED，问题0。原model worker等待ACK时每100ms仍检查原租约/预算/SQL停止，Work未提前移除、没有queue-and-forget或扩大模型池。停止短锁只更新终态，不做网络IO；SQL答案先完整提交再发送done，断线仍不取消持久化任务。原键已提交回放异步发送，每帧检查原租约，拒绝也关闭writer。

8a0714f自身十五CI/四ZIP独立核验通过，Linux四项/九HTTP要求新writer实际Tomcat阻塞，饱和时同时两个阻塞，原worker仍可检查围栏；[远端栈](assets/v1.7-cp77/third-remote/slow-writer-worker-excerpt.txt)与两次失败保留。有限writer仍等TCP推进、socket/container超时或关闭，不保证取消瞬间释放；满时拒绝新流但同步可用，已缓冲字节不可撤回。本地44e包内实际Tomcat10.1.16嵌套JAR摘要0e10a073e143b338ddb3d57dfabb62169f61b3f3127733df8111a635b3fb799f、协议字节码默认connectionTimeout60000ms，YAML无覆盖；NIO写有推进可重置，不是整条连接TTL。自然回收Windows与119cee1自身Linux第五项证据另见78，该轮浏览器失败仍保留，部署/TLS/代理待验，不混同配置推断与直接证据。

## CP76双节点助手停止围栏

节点A持有执行中的原生订阅，节点B仅提交共享SQL取消/清空/账户版本事实；A现有100ms周期检查读取这些事实，走75的订阅/物理HTTP关闭及最终事务围栏，不引入新内存协调器。两JVM同一专属H2文件、真实模型HTTP四场景两次验证：B回放不多调模型，B取消的重复409/他人404/单次审计成立，旧模型未放行时A原worker可用；B清空/撤销均无半答案或晚done。证据见[76报告](acceptance/V1.7-checkpoint-76.md)。这是周期观察，不是即时通知或数据库HA验收；跨机器网络分区、MySQL原生HTTP矩阵、全局容量、慢消费者和自定义TLS/代理仍待验。

## CP75默认助手原生流与提交围栏

默认POST /assistant/sessions/{id}/stream已调用原生streamAnswer，在原有有界worker内逐片段消费；空闲100ms检查原授权/原预算/持久化停止状态。generation/token只能带null答案ID及证据，前端标注“片段尚未保存”且不提供复制；只有正常STOP、有效原问题/账号租约及最终SQL事务提交后，done才发布正数答案ID。答案、标题、审计、请求COMPLETED仍原子提交。已完成幂等键回放原答案，不重新调模型；同步/messages及AI关闭的规则模式保持原路径。

浏览器断开不是显式取消，后台仍在原授权/预算内运行；刷新只能手动GET恢复。取消/超时/撤销/清空/失败会取消订阅和原HTTP响应体，临时片段不能落库。模型EOF/length/晚错误不拼规则降级。停止回调独占停止事件，防止worker抢先关闭SSE而丢掉error。

DashScope SDK内层窗口可能延迟原HTTP释放，故以每订阅独立Reactor Context信号约束实际exchange/body，非助手调用不修改。助手原生流使用单个独立JDK客户端：Future先直接取消再取消物理交换，避免Reactor3.6.0把JDK包装取消误报为丢失错误；真实Provider失败原样传播，没有全局忽略异常或自动重试。该专属客户端采用JDK默认TLS/代理，自定义SSL bundle/连接器、跨节点实时取消及慢消费者容量仍需独立验证。证据与失败保留见[75报告](acceptance/V1.7-checkpoint-75.md)。

## CP74历史阶段：底层原生模型流（当时尚未接默认端点）

AssistantAiService.streamAnswer提供每次订阅独立状态的冷Flux，真实DashScope请求显式增量输出，关闭thinking与SDK内部工具执行；使用chatResponse保留finishReason，锁定SDK把线协议stop转为STOP。只接受单一生成、正常STOP和至少一个非空白片段；精确保留片段空白/Unicode，限制总输出10万UTF-16字符和1万响应帧；EOF、length、工具调用、晚错误或STOP后内容均失败，不自动重试、不拼规则降级。调用者取消会向上游传播，但该适配器自身不负责HTTP会话授权、任务预算或SQL提交。

CP74当时Controller仍调用完整answer路径，SQL事务、幂等、显式取消与前端正数答案ID协议未改。CP75另接入默认端点，不能把74取消冷订阅单测当成端点/跨节点取消已交付。历史证据与未验边界见[74报告](acceptance/V1.7-checkpoint-74.md)。

## CP73助手页面原请求意图

助手对话首POST前冻结本人id/username、sessionId、UUID请求键与原问题；只保存在按账号及会话隔离的sessionStorage，不存Token。存储失败不发送，刷新只恢复本地意图，不自动POST。每次网络生命周期捕获原Token，响应头、JSON解码、SSE读取与UI落定均检查身份；账号变化清空当前页面数据但保留原账号本地意图，必须刷新重新加载。

流式POST同时Accept事件流和JSON，使接纳前503/409等仍能返回结构化错误；成功必须得到有效done及一致答案ID，EOF/截断/未知帧不是完成。显式取消是原键POST /request/cancel，不是abort；GET /request +持久化会话回读决定页面状态。取消响应丢失只显示待确认，完成先赢显示原答案，QUEUED/RUNNING不重发；404只允许用户手动继续完全相同的键与问题，终态不复跑。页面原请求未核清时禁止新问题/清空/删除；同一原流与GET并发不能将已经观察的终态降回运行态。退出组件只断开网络，不取消后台任务。

正文可能敏感，终态核对后清除本地意图、关闭标签页也失去该记录；其他标签页不共享它，不等于永久的服务端操作列表或生产多浏览器恢复。后端V32/V33、共享容量/队列预算仍按[72](acceptance/V1.7-checkpoint-72.md)，新页面验收边界见[73](acceptance/V1.7-checkpoint-73.md)。Agent调查流/生产执行器和模型原生token流没有因此改变。

## CP65/66账号会话与本人安全页

共享SQL库的sys_user.auth_version作为全部已签发会话的撤销版本；JWT签名之外必须核对精确uid/username/sv与当前ACTIVE账号，jti只确保独立签发唯一，不是逐设备注销黑名单。本人改密/全部撤销在行锁下重查身份/版本，递增版本与审计同事务；改密同时更新现有BCrypt哈希。UTF-8字节/有效Unicode边界在哈希前检查，版本上限拒绝而不回绕。默认H2文件库同步写由实际强停后丢改密记录的反例驱动；自定义DB_URL/数据库回滚/硬件持久性另有边界，见[操作说明](AUTH-SESSIONS.md)。

Vue本人安全页区分错误当前密码与已撤销会话，10秒命令预算、busy互锁、不保存口令草稿、不自动重试不确定的密码命令；成功或不确定后清理原凭证并提示本人重登。旧请求的401只可失效原Token，不能清理后来新身份。SPA精确HTML深链开放但所有数据API继续鉴权。65十五CI/MySQL86与Linux重启已验，66本地双时区375/282/93及十二实页脚本通过，66代码7705b8d自身十五CI/MySQL86零跳过/四ZIP摘要与重放已验，见[65](acceptance/V1.7-checkpoint-65.md)/[66](acceptance/V1.7-checkpoint-66.md)。已建立SSE、自己的流式fetch晚响应、已在执行的后台任务及在途请求再授权仍待后续实现，不将入口撤销称为原子取消。

## 1. 设计目标

OpsPilot 解决的是企业信息系统发生故障后的协同闭环，而不是物理设备预测性维护。系统必须回答十个问题：

1. 哪些原始事件属于同一问题？
2. 哪项业务服务受影响，依赖链是什么？
3. 当前谁负责，超时后通知谁？
4. Incident 当前处于哪个阶段，下一步允许做什么？
5. 根因判断引用了哪些告警、指标、日志、变更和手册证据？
6. 值班人员能否围绕同一 Incident 持续追问而不丢失证据上下文？
7. 调查连接断开后，能否恢复过程并保证结果继续落库？
8. 高风险建议由谁独立复核，如何避免自批和并发覆盖？
9. 同一种故障是否跨 Incident 复发，长期问题、根因和规避方案由谁维护？
10. 谁在什么时间执行了什么操作？

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
| Analytics | 响应里程碑口径、严重等级分布、慢事故和行动项运营 | 篡改源数据或推断不存在的里程碑 |
| Problem | 精确指纹复发候选、跨事故关联、已知错误和长期解决生命周期 | 在没有标注与评测时冒充语义聚类或自动根因判断 |
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

### 值班升级执行

Flyway V24 将策略与严重度关联，并通过 `incident_escalation_event(incident_id, step_id)` 唯一约束固化每次到期步骤。新告警创建 Incident 的事务内执行零分钟步骤，定时任务每分钟扫描最多 100 个仍为 `OPEN` 且有未执行到期步骤的候选；管理员/运维经理可即时扫描。执行前 `FOR UPDATE` 锁定 Incident，再检查状态和事件，以跨实例并发时不重复写站内路由、时间线与审计。`ACKNOWLEDGED` 后停止，不把 `INVESTIGATING` 或已恢复事故误判为未确认。

事故创建、两小时聚合窗口、当前班次和默认升级扫描统一使用数据库会话的 `CURRENT_TIMESTAMP`，与确认/恢复里程碑保持同一时钟，避免 UTC 环境下混入硬编码上海应用时间导致八小时偏差。现有时间字段没有逐条保存时区；排班输入须与数据库会话时区一致，此修复不等于多时区排班支持。

ON_CALL 只在策略引用的同一服务班次中选活跃用户，重叠时按覆盖标记、开始时间、ID 决定唯一接收人；USER/ROLE 也只取活跃账号。无目标时固化 `NO_TARGET` 且不插入通知日志；有目标时写 `ROUTED` 与 `notification_log` 的 `IN_APP/RECORDED`，仅表示站内路由事实，未证明外部送达或本人已读。迟到扫描会补执行所有已到期步骤，避免重启后静默遗漏；若需外部消息、确认回执或高吞吐调度，仍需独立建设。此流程参考 [Grafana IRM 的接入、分组与逐级升级](https://grafana.com/docs/grafana-cloud/observe-and-act/respond-to-incidents/introduction/routing-and-escalation/)，但没有实现其外部通知能力。

V25 增加普通/临时覆盖的班次维护与软取消。创建/取消按同一 schedule 行锁串行化，重叠检查再用锁定读保证 MySQL 最新可见性；同类未取消区间不可重叠，`[starts_at, ends_at)` 允许边界交接，覆盖与普通班次可重叠。取消需要 version/原因，递增版本并写审计，当前班次/后续路由排除取消行；已有升级事实不改写。只允许活跃运维职责用户，查询最多 31 天/200 条并提示截断。页面用服务端数据库时间生成输入，不进行浏览器时区换算；整秒校验匹配现有 MySQL TIMESTAMP 精度。这个维护入口不等于自动轮转或跨时区排班。

checkpoint 44 为接入/扫描显式指定 READ_COMMITTED：候选或 CMDB 查询不能把后续策略、班次和账号读取冻结在 MySQL 默认 REPEATABLE_READ 的旧快照里。路由与告警仍同事务，Incident 行锁和步骤唯一约束不变；提交在对应查询之前的取消/覆盖/停用可见，但查询之后的变动不追溯撤销事实。REQUIRED 加入外层事务时不能覆盖外层隔离级别，因此未来组合事务须明确遵循 READ_COMMITTED 契约；不是全局修改数据库默认值，也不新增连接池嵌套读。H2 与真实 MySQL 共用提交闸门与六类事实回滚断言，直接结果见验收报告。

### 班次轮转与自动续排

V26 的轮转规则固化锚点、班长、有序成员；时段序号驱动 round-robin，物化为同一普通班次模型，临时覆盖仍按原规则优先。台账/班次来源唯一键与 schedule/rotation/user 的固定锁序保证重启与并发不重复，取消后的生成时段保留 tombstone 不复活。默认每分钟向前维持 14 天开始的时段，不回填无限历史；与普通班次冲突或成员不可用时记录明确状态，不自动覆盖/跳过。已有生成事实与账号当前资格分开显示，ON_CALL 选择排除失去运维职责的用户。

每条规则独立短事务生成，一条失败回滚且其他规则继续；人工创建/变更保留操作者，后台按系统/scheduler 审计。暂停停止续排但不取消既有班次。规则 API 与有序成员/状态/异常台账 UI 已接入，页面捕获版本，409 后阻止旧提交，手工班与轮转操作互相刷新。目前沿用固定数据库会话本地时间，不宣称多时区/DST。新接口、真实后台与验证边界见 [checkpoint 45](acceptance/V1.7-checkpoint-45.md)，页面和新旧截图见 [checkpoint 46](acceptance/V1.7-checkpoint-46.md)。

### 已落库班次的日历覆盖

覆盖预览与路由目的不同：路由入口使用 READ_COMMITTED 看每次查询前的已提交变化；只读覆盖查询使用 REPEATABLE_READ，把计划、班次与当前成员资格作为同一次快照解释，不能把不同时间的片段拼成一个健康结论。查询窗口首尾及所有源班次起止形成有界分段，半开区间只选一个胜出班次，遮盖/无资格/同层重叠单独解释。取消不参与，未物化的轮转不作预测。窗口最多 31 天；超过 1000 条未取消源班次直接拒算而非截断，避免漏掉优先覆盖或缺班。

日历按无时区的数据库本地日期裁剪，跨午夜/闰日计时不重复；每日日历卡片可筛选裁剪后的有效时间线。汇总是可路由覆盖，不是 SLO，未来/历史窗口都只采用当前账号资格，不能冒充历史资格或未来通知保证。后端共享场景、桌面/移动与第三份浏览器 CI 见 [checkpoint 48](acceptance/V1.7-checkpoint-48.md)。

### 双方同意的定向接班

V27 保存申请时的原班次 ID/版本、双方、UUID、时段和原因。申请只允许原普通班次负责人，接受/拒绝只允许指定接班人，撤回只允许申请人；ADMIN 权限不等于可以代替本人同意。新职责通过已有临时覆盖模型表达，原班次/轮转时段及旧 Incident 路由不改写。

| 从 PENDING 出发 | 本人 | 原子结果 |
| --- | --- | --- |
| ACCEPTED | 指定接班人 | 新覆盖 + 请求版本/决策 + 两条审计 |
| REJECTED | 指定接班人 | 请求版本/决策 + 审计，无覆盖 |
| WITHDRAWN | 申请人 | 请求版本/决策 + 审计，无覆盖 |

所有变更遵循 `计划行锁 → 请求行锁（决策）→ 双方用户按 ID 升序锁（接受/创建）→ 源版本/重叠重查 → 同事务写入`。READ_COMMITTED 读取已提交状态；外层组合事务必须遵守相同隔离级别。新普通/覆盖班次和轮转都持有同一计划锁，因此申请期间保存的旧版本不能绕过新的取消或覆盖。多个不同请求竞争同一时段只能一个接受，其他保留 PENDING 并返回409，而非静默覆盖。

创建重试以申请人+规范UUID唯一且逐项匹配不可变载荷；决策重试要求终态、原请求版本与说明都匹配。已接受覆盖后来被软取消，重试也不新建、不复活。已开始的请求在所有锁等待后读取 `CURRENT_TIMESTAMP(6)`，向上取整秒形成剩余覆盖，不把整段申请时间补写到过去；H2须维持 MODE=MySQL（时钟按语句，而非普通模式按事务）。过期只禁止接受，不伪造自动EXPIRED任务。拒绝/撤回不依赖原班次仍有效或计划仍开启，便于清理失效请求。

接班后端与真实JAR HTTP见 [checkpoint 49](acceptance/V1.7-checkpoint-49.md)，列表在SQL内先应用计划/身份/状态筛选再截断，MINE从认证主体匹配双方，共享场景扩至17项，见 [checkpoint 50](acceptance/V1.7-checkpoint-50.md)。

请求页面见 [checkpoint 51](acceptance/V1.7-checkpoint-51.md)：源班次入口捕获ID/版本，不凭管理角色代替本人同意。首次POST前保存按账号分区的sessionStorage草稿；键与内容一起冻结，刷新只恢复而不自动POST，手动重试仍发送原载荷。明确放弃本地草稿不代表撤回服务器请求，标签页关闭不承诺草稿仍在。决定保留原版本/说明，网络失败允许手动原样重试，409表单锁定，刷新不会自动改版本。接受后仅刷新只读视图，不重复创建覆盖；界面角色按钮是提示，真正权限/资格/时间/版本门禁始终在服务端。

checkpoint53新增V28独立撤销台账与覆盖快照API：保留ACCEPTED请求/原接受人时间版本，联表读取实际覆盖取消和可选撤销记录；旧班次维护直接取消不会伪造管理撤销事实。管理角色以原请求/覆盖版本、UUID与理由操作，计划/请求/最新管理人行锁、跨计划键串行化、READ_COMMITTED重查；覆盖取消、独立台账、班次与请求审计同事务。已结束覆盖新操作拒绝，已提交同意图原键可回执；当前管理资格仍重查，新键/不同意图不会复活覆盖。源班次也可能失效，不能承诺取消后必然恢复原负责人；历史路由事实不改，日历不是历史责任时点重建。

checkpoint54界面将原决定/实际覆盖/独立撤销分栏展示。详情读取失败清空旧快照，不留下可操作的旧状态；首次POST前保存按账号版本化的sessionStorage撤销草稿，双版本/键/说明冻结，刷新恢复不自动提交，手动重试原载荷。409/403锁定意图可跨刷新保留，明确放弃仅清本地草稿而不恢复覆盖；成功刷新已有班次/轮转/覆盖日历。标签页关闭不保证草稿保留；安全权限始终以服务端最新角色/资格为准。开放认领、双向互换、外部提醒、日历同步及历史责任时点重建未完成。

### 无责复盘与防复发行动

复盘不是调查阶段的即时总结。只有 `RESOLVED/CLOSED` Incident 才能创建，创建事务会先读取当时已有的时间线、告警、最近调查报告和相关变更，经过 `LogRedactor` 后保存 JSON 快照，再写 `POSTMORTEM_CREATED` 事件。因此后续新增时间线不会悄悄改变复盘依据，重复创建也只返回同一份草稿。

草稿包含事件摘要、用户/业务影响、直接与系统性原因、促成因素、经验与改进五类正文。任一字段为空或仍含 `【待补充】`，以及没有行动项时，均不能进入复核。行动项必须指向具有运维处置权限的活跃用户并设置不早于当天的截止日期；父复盘和子行动项分别带版本号，新增/编辑子项也先递增父版本，避免提交与修改并发穿透。

提交后正文和行动项冻结，只有 `ADMIN/OPS_MANAGER` 可以复核，且提交人不能复核自己的内容。`REQUEST_CHANGES` 回到草稿，`PUBLISH` 后正文永久只读；行动项仍由负责人或管理角色完成，并在 Incident 时间线和审计日志中留下引用。这个边界参考 [Google SRE 的 Postmortem Culture](https://sre.google/sre-book/postmortem-culture/) 对影响、原因、行动项、无责文化和正式复核的要求，以及 [FireHydrant retrospectives](https://docs.firehydrant.com/docs/conducting-retrospectives) 与 [follow-ups](https://docs.firehydrant.com/docs/managing-follow-ups) 对事故证据汇集、复盘后跟踪工作的划分。OpsPilot 实现的是本项目内的确定性治理流程，不宣称复制这些产品的全部能力。

### 事故指标与行动项运营

指标窗口按 `incident.created_at` 纳入，`from/to` 均包含，默认最近 30 日、最长 366 日；严重等级是可选过滤条件。MTTA 读取 `acknowledged_at - created_at`，MTTM 读取时间线中首次进入 `MITIGATED` 的时间，MTTR 读取 `resolved_at - created_at`。每个指标只接纳自身里程碑存在且时长非负的样本，分别返回样本数、均值和中位数；缺失不补 0，异常值不改写源 Incident。最慢已恢复事故和严重等级分布用于下钻解释，而不是用单个平均数代替事故详情。

行动项运营查询跨越单份复盘，按全部/本人、开放/完成和是否逾期筛选。逾期定义为 `OPEN && due_date < Asia/Shanghai 业务日`，截止当天不算逾期。定时任务每日 09:05 扫描，管理角色也可以显式传业务日期复现边界；每个候选先锁定行动项，再由 `follow_up_id` 唯一约束保证最多一条升级事实。重复扫描只计为已存在，不重复写 Incident 时间线和审计。完成行动项时同一事务关闭开放升级事实并保留首次发现/关闭元数据。

`postmortem_follow_up_escalation` 表示“系统发现并治理逾期”的内部事实，不复用 `notification_log`，也不代表邮件、短信或企业 IM 已送达。指标筛选提供事故窗口；行动项摘要始终返回当前业务日状态，因为当前模型不是保存每次负责人/状态变化的双时态历史库。

### 重复事故与 Problem Management

复发识别使用高精度、低召回的确定性基线：在最多 366 日的 Incident 创建窗口内，只有相同归属服务、相同 `alert_event.fingerprint` 且关联至少两个不同 Incident 的信号才形成候选。分母固定为 `COUNT(DISTINCT incident_id)`；同一事故中 `occurrence_count=100` 仍是一个事故证据，告警发生总量只用于说明噪声和影响规模。候选同时返回匹配依据、不同日期数、首次/最近事故、未关闭事故数和下钻明细，不返回没有训练/标注依据的相似度概率。

管理角色可将候选提升为 `problem_record`。`recurrence_key=serviceId:fingerprint` 的唯一约束与事务内冲突复用共同保证重复请求和并发点击只产生一个 Problem；冲突失败者会暂停已经持有旧快照的事务，并在 `REQUIRES_NEW` 新事务中完成赢家回收、缺失关联同步和最终视图读取。真实 MySQL 闸门证明只把主键回读改成 `SELECT ... FOR UPDATE` 仍不够，因为同一事务后续普通读取仍会使用既有 REPEATABLE READ 快照。`problem_incident_link(problem_id, incident_id)` 的唯一约束保证历史证据与时间线幂等。创建时固化当前窗口的所有匹配 Incident，以后 Alert Intake 在完成告警聚合后按服务与指纹查找已登记 Problem，并自动补充新 Incident 关联。

Problem 状态为 `OPEN / KNOWN_ERROR / RESOLVED`：已知错误必须同时具备已确认根因与可执行规避方案，解决必须具备长期解决说明，更新使用 `expectedVersion` 乐观锁。已解决后出现新匹配 Incident 时，系统保留 `RESOLVED` 并计算 `recurredAfterResolution=true`，要求负责人显式判断是否重开，避免后台任务静默改写治理结论。该模型参考 ITIL Problem/known error 的职责划分，但当前不声称具备 PagerDuty 式机器学习相似度、跨服务因果聚类或外部 Jira 同步。

### 版本绑定的 SLO 原生告警规则

服务 SLO 的界面评估与规则导出共用预算策略：阈值为目标周期小时数×预算比例/长窗口小时数；1/2天目标相应缩短票据窗口。有效计数保留精度到判定，避免极小正分母被舍为零。只读导出在REPEATABLE_READ快照中核对当前活跃管理角色、目标版本和启用状态，产生带目标版本标签、策略版本和SHA256的确定性规则文件。

规则文件由运维经原生promtool校验后发布到Prometheus：好/总事件记录→有限且有效的窗口燃烧率→长短窗口与优先级告警→Alertmanager→既有webhook幂等入站。每30秒评估，记录超过60秒或无效数据独立告警；该新鲜度不证明原始数据的采集完整性。规则替换由运维管理，目标数据库更新不会重载生产Prometheus，旧版本导出409阻断。恢复仍保留Incident人工治理。具体发布和数据边界见[规则说明](SLO-PROMETHEUS-RULES.md)与[检查点58](acceptance/V1.7-checkpoint-58.md)。

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

调查请求进入有界 `ThreadPoolTaskExecutor`，避免高峰期无界创建线程。JWT 用户 ID 和请求 IP 在进入异步线程前捕获，保证安全上下文不会因线程切换丢失。客户端断开只关闭发送通道，不中断后台调查；run、报告、时间线和审计仍会完成。多实例通过同事务 outbox、Redis Streams 通知和数据库补读推进订阅；数据库仍是事件正文、顺序和权限的事实源。

事件包括 `RUN_STARTED`、计划完成、步骤开始、证据采集、步骤失败、再规划、动作建议、运行完成和运行失败。页面展示的是系统已执行并落库的事件，不是模型隐式推理。

### 运行控制与并发边界

流式入口先执行 `prepare`，将 run 以 `QUEUED` 状态和 `RUN_QUEUED` 事件提交，再交给有界执行器。调用方可发送 `Idempotency-Key`；数据库约束保证同一 Incident 的同一键最多对应一个 run，重复请求只返回原 run 的持久化事件快照，并通过响应头标明回放。

每个 run 保存 `deadline_at`。请求级 `timeoutMs` 受服务端最大预算限制，单线程看门狗在截止时登记超时请求并尽力中断任务；显式取消接口同样先保存申请人、原因、时间和请求事件，再取消受管 `FutureTask`。编排器在工具边界检查终止请求，最终写入 `CANCELLED` 或 `TIMED_OUT`，且不生成报告和处置提案。外部客户端若不响应线程中断，Provider 自身的连接/读取超时仍限制单步阻塞时间。

看门狗和任务句柄都是实例本地状态；执行 JVM 退出后，存活实例的 `AgentRunRecoveryJob` 以有界批次扫描已逾期活动 run。它对每个 run 在独立短事务内加行锁并重检状态，因此多实例并发扫描只会有一个结算者。结算会写入终态事件、Incident 时间线和审计；原执行线程如果仅是晚返回，会在保存工具结果前被终止检查拦截。当前不自动重放工具链，不是跨实例任务迁移，也不承诺 exactly-once 执行。

执行器饱和时不丢失请求事实：run 转为 `QUEUE_REJECTED` 并追加 `RUN_REJECTED`。所有事件由 run 行上的 `next_event_sequence` 在事务中串行分配，避免工作线程与取消/超时线程竞争时出现重复序号。

### 可观测数据 Provider

指标和日志分别通过 `MetricsProvider`、`LogsProvider` 接口接入，Router 按优先级选择可用实现。默认本地 Provider 从 Flyway V4 的指标样本和日志事件表读取可复现证据；启用 `PROMETHEUS_ENABLED` 后，Prometheus Provider 优先调用 `/api/v1/query`；启用 `LOKI_ENABLED` 后，Loki Provider 优先调用 `/loki/api/v1/query_range`。任一外部 Provider 失败时携带降级原因回退到对应本地证据。

外部调用配置连接/读取超时，使用 Spring Retry 做单次请求内重试，并由 `ProviderGuard` 在多次请求间维护 `CLOSED / OPEN / HALF_OPEN` 状态。Agent 步骤保存 Provider、查询表达式、时间窗、外部引用和 warning，避免只保存一段不可溯源的自然语言。Loki 支持 `X-Scope-OrgID` 与 Bearer Token，按纳秒时间戳、返回上限和倒序查询日志 stream；常见 JSON 日志中的 message、level、logger、traceId 会转换为结构化证据。日志在进入证据链前屏蔽密码、Token、Authorization、邮箱和完整 IPv4；原始敏感值不写入调查报告。

服务 SLO 不经过 Metrics Router 的本地降级链：它必须从 Prometheus 分别取得好事件和总事件，且两条查询都只能归约为一个数值。Flyway V19 保存目标、滚动窗口和 PromQL 模板；评估器据此计算 `SLI = good / total`、`error budget = total × (1 - target)` 与 `burn rate = error rate / (1 - target)`。燃烧率对 `5m / 30m / 1h / 6h / 3d` 五个唯一窗口取样，三档策略只有在长/短两窗口同时超过 `14.4x / 6x / 1x` 时才分级为 PAGE 或工单。Provider 关闭/失败、空结果、零分母、多序列或 `good > total` 时拒绝计算，避免将可复现 Demo 指标误称为生产 SLI。目标修改使用角色权限、乐观锁和审计；当前窗口查询按需直接执行，Prometheus recording rules、低流量样本策略与 Alertmanager 通知仍由后续检查点完成。

### Alertmanager 入站边界与幂等模型

Alertmanager 通过专用 webhook 进入 `AlertmanagerWebhookService -> AlertService -> alert_event / incident / timeline`。适配器遵循[官方通知结构](https://github.com/prometheus/alertmanager/blob/main/docs/notifications.md)，但只接受能明确映射资源和严重度的 alert；不会用默认服务或默认优先级掩盖配置错误。端点默认关闭，启用后用独立共享密钥认证并限制批大小。

生命周期 ID 为 `fingerprint:startsAtEpochMillis`。数据库已有的 `source + external_event_id` 唯一约束提供最终幂等保证：同状态重试返回 `REPLAYED` 且零写入；状态变化返回 `UPDATED`，不增加 occurrence，并写一次 Incident 状态时间线。没有外部 ID 的原有 intake 继续按 30 分钟 firing 指纹累计 occurrence，两类语义明确分离。批内可预期业务错误作为逐项拒绝返回，未知运行时/数据库故障不被吞掉，使 Alertmanager 可以重试整批。

V20 将永久业务拒绝写入 `alert_ingest_rejection`。对完整 alert 使用 `fingerprint + startsAt + status` 的 SHA-256 键，故 firing 的动态 `endsAt` 不会制造多条拒绝；不完整 alert 才回退到规范化 JSON 哈希。存储的是经 `LogRedactor` 处理的稳定快照，列表不返回 payload。手工重放使用 30 秒 token 租约，令牌一致时才能完成/释放；再通过 `source + external_event_id` 幂等键防止“告警已创建但台账回写失败”导致重复。查看包含 AUDITOR，重放只对 ADMIN/OPS_MANAGER/ON_CALL 开放，成功/失败写审计。当前仍是入站 receiver，不包含 SLO 出站发送、送达回执、自动退避重放或拒绝快照保留期。

适配器协议以 [Prometheus HTTP API](https://prometheus.io/docs/prometheus/latest/querying/api/) 和 [Grafana Loki HTTP API](https://grafana.com/docs/loki/latest/reference/loki-http-api/) 为准，并通过本机临时 HTTP 服务验证请求参数、请求头、响应格式和降级契约。

### Runbook 知识库与检索门禁

V1 的 `runbook` 表和浏览器 `contains` 搜索保留为迁移来源及效果基线。V1.6 新增稳定文档键与不可变版本，历史实现按正文哈希复用并在导入时替换发布版。检查点56的V30改为同一提交人、正文、来源、元数据、规范化ACL及发布基线共同识别相同待审候选；导入只创建PENDING_REVIEW，另一当前管理账号批准后才替换旧发布版。每个版本保存来源、资源/服务元数据和角色 ACL；Markdown 按标题分段并限制分块长度，PDF 由 PDFBox 提取文本后进入同一处理链。

```text
Markdown / extractable PDF
  -> validate size, type and metadata
  -> immutable candidate + submission identity + captured publication baseline
  -> heading-aware candidate chunks + role ACL
  -> independent approval (reject / owner withdraw never enters retrieval)
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

检查点55的V29补非敏感returned_document_count，同一次检索INSERT保存稳定文档键去重数；Java迁移以500行keyset恢复旧ACTIVE结构完整快照，已清理/损坏数据不回填假零。只读管理接口`GET /api/v1/runbooks/searches/trend`固定实际执行引擎、来源和K，先按查询ID聚合独立批准的文档评分，再按查询日聚合。在REPEATABLE_READ事务中读取数据库时钟与统计；默认30天、最大90天。返回率与相关性分开：非空查询全部返回文档完成独立复核才计分，部分正相关不伪装完成；已知空结果计未命中，未知排除并单列，零分母为null。总率由分子分母汇总而非日率平均。K为原协议片段上限，评分按文档去重；不是去重文档Top-K。晚复核更新原查询日，非历史当日评分；只统计成功持久化查询，不外推生产成效。新清理保留计数和已复核结构化事实，避免正文擦除使趋势错误归零。前端保留离线评测，单独显示真实查询趋势、复核覆盖与未知；失败读取清空旧口径，不做隐式重试或造数。

设计还借鉴了 [Backstage TechDocs](https://backstage.io/docs/features/techdocs/) 的 docs-like-code 与可搜索文档思路、[Rundeck](https://docs.rundeck.com/docs/about/introduction.html) 的 Runbook 自动化权限/历史边界，以及 [OpenSearch BM25](https://docs.opensearch.org/latest/im-plugin/similarity/) 的关键词检索模型。当前仍是单机小语料与可选外部 Embedding：没有向量 ANN/OpenSearch，未接 cross-encoder rerank，PDF 不含 OCR。检查点56已将导入即发布改为待审与独立复核；其最终验收仍进行中，见 [阶段报告](acceptance/V1.7-checkpoint-56.md)。

V30为Runbook增加待审/拒绝/撤回、提交发布基线、审核版本、决定键/原始说明哈希与脱敏复核事实；已有发布版本不重写。导入和决定先验证当前账号，再用逻辑手册锁与候选锁串行化。锁行使用原子upsert直接取排他锁，避免InnoDB重复INSERT后共享锁升级的竞态。批准才替换旧发布版，基线不符拒绝，不自动rebase。事务中同时写版本状态/审核/审计；精确重放不重发或复活旧版。普通历史只暴露PUBLISHED/SUPERSEDED，所有既有检索候选仍只读PUBLISHED；审核台账只返回元数据，正文由管理详情读取。只读详情/台账采用一致快照，命令回包使用锁定当前读，防止旧谓词快照让发布版本与状态矛盾。UI在首个POST前冻结账号、ID、版本、键、决定及说明，网络结果未知手动同键恢复；权限或冲突拒绝锁定，放弃本地草稿不撤销服务端事实。

冻结意图校验独立于页面GET：损坏JSON、字段错误或账号不匹配均保留原始记录并阻断新提交，不能悄悄换键；quota失败发生在POST前。详情404或队列读取失败不遮住核对/明确放弃入口。会话恢复仅GET，不自动发决定，发送前再次校验当前账号；失败锁持久化失败也保持当前页面阻断。此状态只保存在当前浏览器sessionStorage，不是服务端跨设备执行租约。

检查点57将PublicationDecisionRequest.expectedVersion改为@NotNull @Min(0) Integer：区分合法初始版本0和缺失/null，不让JSON绑定将后两者默认为0后进入决定事务。六例真实HTTP拒绝/补0接受及MockMvc拒绝后状态/审核/审计无副作用验证，范围见[字段契约报告](acceptance/V1.7-checkpoint-57.md)。没有改变数据库版本或其他业务DTO，也不宣称禁用了全局JSON数值强制转换。

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

密码使用 BCrypt，JWT签名验证后每次请求重新加载当前账户/角色，并在建立认证前检查UserDetails账户资格；停用/删除拒绝新请求并返回401，活跃角色不足403。checkpoint59补齐此前遗漏的资格检查，真实HTTP先复现停用后读身份/写备注仍200，再验证阻断和业务零新增。checkpoint60进一步要求已签发uid为正的精确64位JSON整数，sub为不超过64个Unicode字符的非空文本、exp必须存在且为正整数；当前账户数据库ID必须匹配uid，防止同名重建且分配新ID的账户继承旧Token。权限仍来自当前数据库角色，不信任Token中的role。

时间反序列化发生在签名检查之前；极值exp/iat/nbf即使签名错误也曾造成DateTimeException和500。JwtService只在库验证调用周围将该时间异常转换为JWTVerificationException，沿用入口结构化401，不捕获所有运行时错误，也不自行实现签名验证。H2/MySQL共享真实HTTP场景同时检查读写拒绝、备注/审计零新增及正常登录恢复，实际JAR畸形Token门禁不读取运行时密钥。最新验证范围见[60阶段证据](acceptance/V1.7-checkpoint-60.md)。不宣称永久撤销：重新启用同一ID后未过期旧Token可用；人工ID复用/数据库回滚、密码变更撤销与既有SSE/后台任务再授权仍未实现。关键状态流转、分派、备注和调查均写入`audit_log`。

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
cmdb_resource 1---n problem_record 1---n problem_incident_link n---1 incident
postmortem_follow_up 1---0..1 postmortem_follow_up_escalation
sys_user 1---n incident_postmortem(created/submitted/reviewed)
sys_user 1---n postmortem_follow_up(owner/created/completed)
sys_user 1---n postmortem_follow_up_escalation(created/resolved)
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
oncall_shift(source) 1---n oncall_handoff 1---0..1 oncall_shift(replacement)
sys_user 1---n oncall_handoff(requester/target/decider)
cmdb_resource 1---n escalation_policy 1---n escalation_step
incident 1---n incident_escalation_event n---1 escalation_step
```

数据库变更由 Flyway 管理。H2 使用 MySQL 兼容模式保证本地零配置体验，Compose 提供 MySQL 部署路径；Testcontainers 条件套件从真实 MySQL 8.4 空库执行迁移，并验证关键索引、中文数据、Runbook 召回、调查主链路、复盘/行动项、Problem/SLO、Alertmanager 与值班升级台账。`flyway-mysql` 作为正式运行依赖加载 MySQL 方言支持；最新迁移版本与直接证据以验收报告为准。

## 9. 可观测性和失败策略

- 独立管理监听 `127.0.0.1:9920`（JAR 默认）提供 `/actuator/health` 和 `/actuator/prometheus`：存活/依赖健康及 JVM、HTTP、连接池指标；业务端口 `9900` 不映射这些端点。Compose 为内部 Prometheus 抓取改为容器内 `0.0.0.0:9920`，宿主机仅回环映射 `9920`，内网仍须网络隔离。
- `/api/v1/observability/providers`：Provider 启用状态、优先级和熔断状态。
- Micrometer Tracing 以 OpenTelemetry bridge 串联 HTTP 请求、告警接入、异步 Agent run、工具步骤和 Provider 调用；Prometheus/Loki 复用 Spring Boot 管理的 `RestClient.Builder` 创建 HTTP client span 并注入 W3C `traceparent`；启用时通过 OTLP/HTTP 导出。
- API 错误统一返回 `success/data/error/timestamp`。
- 参数错误返回 400，认证失败 401，权限不足 403，状态/版本冲突 409。
- AI 不可用不影响告警、Incident 和审计主链路。
- 工具无数据与工具失败明确区分；失败原因随步骤持久化，其他证据源继续执行。
- Agent 事件在数据库与 outbox 同事务提交，Redis Streams 只作跨实例唤醒，数据库补读与 SSE 游标负责回放。提交后进程退出不会丢失已提交事件。
- Agent 调查使用幂等键、有界队列、持久化截止时间和显式取消控制；取消、超时和拒绝均进入可审计终态，崩溃留下的活动 run 在 deadline 后由协调器幂等结算。
- Prometheus/Loki 失败由超时、重试、跨请求熔断和本地 Provider 降级保护；日志进入证据链前脱敏。
- 高风险提案只能独立审批，使用 RBAC、自批禁止和乐观锁保护；审批不会自动触发生产变更。
- 逾期扫描使用唯一事实、行锁和幂等时间线/审计；内部升级不冒充外部通知送达。

Trace 只记录受控业务字段：Alert/Incident/run/report ID、来源、严重级别、触发方式、工具/Provider 名称、结果状态与证据数。其中业务 ID 本身仍是高基数 attribute，仅用于 trace 检索，不转成 metrics label。告警标题/描述、labels、PromQL/LogQL、Token、Authorization 和 IP 不进入 span attribute。`AgentExecutionManager` 在提交前捕获当前 HTTP span，工作线程在其 scope 内创建 run span，因此原请求 span 先结束也不会断链；无父 span 时 run 自然成为新根。

默认 `OTEL_TRACING_ENABLED=false`，保持零外部依赖演示；部署时显式配置 Collector endpoint 和采样率。可选 Compose profile 形成 `OpsPilot -> OTLP/HTTP -> OpenTelemetry Collector -> OTLP/gRPC -> Tempo -> Grafana` 链路：Collector 在入口使用 memory limiter 与 batch，在出口启用有界发送队列和指数退避；Tempo 的本地后端仅保留 24 小时，定位为开发/验收环境，不冒充生产对象存储集群。独立门禁会通过真实告警和 Agent 调查生成 TraceQL 可检索链路，再经 Grafana 数据源代理读回同一 trace；它还会停止 Tempo，直接证明调查与应用健康不受下游故障影响，并在后端恢复后读回 Collector 重试成功的完整 trace。checkpoint-24 用真实 TCP HTTP 端点证明 Prometheus/Loki 出站请求携带 W3C `traceparent`，header spanId 与导出的 HTTP client span 匹配。checkpoint-25 进一步引入仅在 `tracing-test` profile 运行的独立 Spring Boot Provider fixture，由 OpsPilot 实际调用并把两个 JVM 的 span 导入同一 Tempo trace；Run 58 门禁按 `resource.service.name`、span kind、traceId、parentSpanId 和指标/日志端点名验证两条 `provider.query -> CLIENT -> SERVER` 分支，正常与后端短暂故障恢复均通过。fixture 不能等同真实生产 Prometheus/Loki 服务端；Collector 重启后持久队列、对象存储与生产采样成本也尚未验证。实现依据 [Spring Boot 3.2 RestClient](https://docs.spring.io/spring-boot/docs/3.2.3/reference/htmlsingle/#io.rest-client.restclient)、[OpenTelemetry 上下文传播](https://opentelemetry.io/docs/concepts/context-propagation/)、[OpenTelemetry Trace API](https://opentelemetry.io/docs/specs/otel/trace/api/)、[OpenTelemetry Collector](https://opentelemetry.io/docs/collector/) 与 [Tempo HTTP API](https://grafana.com/docs/tempo/latest/api_docs/)。

## 10. 交付与回归门禁

checkpoint61补Collector出口持久队列：`file_storage`引用专属命名卷、fsync写入、每bbolt文件256MiB增长上限；非root10001:10001运行，一次性init将卷根目录设0750。当前固定版本的隔离实验已在Tempo停止时SIGKILL并重建Collector，再恢复同一原Trace完整图，旧内存配置/首失败保留；此前段落所述“尚未验证”是历史checkpoint25的范围。本实验的单消费者仅为测试诊断，生产仍默认十消费者；不是Exactly Once、SDK未入队零丢失或生产容量结论，详见[队列运行边界](COLLECTOR-QUEUE-RECOVERY.md)和[61证据](acceptance/V1.7-checkpoint-61.md)。

checkpoint62进一步验证默认十消费者在途项跨SIGKILL/新容器恢复，原完整图/10个已知ID探针均找回；requests队列大小包含在途项，不与in-flight相加。指标只监听容器网络8888，可选Prometheus覆盖保留业务抓取、加入实际queue gauge/拒绝counter规则；原生评估与真实队满实验触发并恢复告警。batch异步接收200不代表已入队：12已知探针可全缺失，业务仍COMPLETED/UP，恢复后独立同入口已知ID正对照必须送达。首轮ID省略前导0、懒counter和背景流量问题均有失败证据。62范围未配置Collector告警的Alertmanager路由，不借其他链路成功声称Incident接入；详见[62限定证据](acceptance/V1.7-checkpoint-62.md)。

checkpoint63通过独立可选覆盖补Collector原生告警→受控Incident：专属Alertmanager严格匹配alertname/job/resource_code，以只读文件凭证认证，unmatched不转发；CMDB的本地Demo登记是显式SQL而非Flyway/自动发现，普通离线与62监控-only覆盖不改。未知库存保留拒绝，修复后native重复送达回填原拒绝；同生命周期重复无写、恢复原ID与单条时间线，Incident仍OPEN无人工分派/接单。queue4/batch1在恢复后仍真实过载的第二次失败保留；63测试改16/32并维持应用Trace、原30秒hold/5分钟窗口，补清空后counter447稳定到resolved。生产仍2048/默认10/batch512，不能由测试推断容量。7e81395十四CI/四工件/原始重放通过，见[63证据](acceptance/V1.7-checkpoint-63.md)与[操作边界](COLLECTOR-ALERTING.md)。

- 默认 Maven 套件以 H2 覆盖领域规则、HTTP API、事件回放、运行控制、审批和外部 Provider 契约；MySQL Testcontainers 用系统属性显式启用，避免开发机没有 Docker 时误报失败。
- GitHub Actions 独立执行前端生产构建、H2 测试与 JAR、MySQL 8.4、Redis、双 JVM SSE，以及 Collector/Tempo/Grafana Trace 端到端集成；全部前置门禁通过后才构建最终容器镜像。
- 镜像门禁不止检查 `docker build`：还以非 root `opspilot` 用户启动容器，并轮询 `/actuator/health`。镜像预建可写 `/app/data`，保证默认 H2 数据文件能在最小权限下创建。
- SSE emitter 断开测试确认网络连接消失只停止发送，不把异常传播到后台调查；有界执行器测试用一个工作线程和一个队列槽位稳定复现排队、取消和第三个请求拒绝。

## 11. 后续演进

1. Alert Intake 前置 Kafka，用唯一键与消费者幂等扩展吞吐。
2. 通知模块接企业微信、短信或邮件，把已实现的内部逾期事实发送到外部渠道，并实现确认回执、重试和死信。
3. 将数据范围权限细化到部门、系统和资源负责人。
4. 增加系统级并发压测、真实 socket 断流恢复，以及外部 Provider 组合故障注入。
5. 为多实例事件广播和任务协调接入消息组件。
6. 默认助手原生MySQL单JVM HTTP18、82原五慢消费者与84自己的3abb34c9/Run37154511110十七CI/真实MySQL8.4.11双JVM六项已独立核验，原失败保留；84新增两方向排队取消/真实503和未放行占用模型时复用被拒原session/key，原预算/worker/队列不改。独立父测试保留JDBC/最终SQL/双节点连接/完整双池关闭门禁，并查四条完成正对照和两条取消请求null问题ID审计。继续跨机器、DB HA/分区、TLS/代理/trickle与生产容量，不把模型释放等同输出writer即时释放；[84自身闭环](acceptance/V1.7-checkpoint-84.md)/[83远端](acceptance/V1.7-checkpoint-83.md)。
7. 在已完成 MTTA/MTTM/MTTR、行动项逾期治理、精确指纹复发、Prometheus 事件型服务 SLO 和 Alertmanager 入站生命周期之上，检查点55补真实检索完整独立复核子集趋势；继续获取长期真实样本，建设跨 Incident 语义相似/依赖共因聚类，并以真实生产 recording rules、长期窗口、出站通知和送达回执验证 SLO。
8. 从真实但脱敏的历史 Incident/查询流量持续扩充已实现的双评分 qrels，加入第三方仲裁、超过两名标注人的一致性和分层抽样；将现有保留任务扩展到备份/导出副本和面向单条数据的受控删除，再以 NDCG/Recall 验证真实 Embedding 与 cross-encoder rerank 是否稳定优于 BM25/RRF，决定是否引入 ANN/OpenSearch/Milvus。
