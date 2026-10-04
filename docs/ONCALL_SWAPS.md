# 双向换班后端契约（CP85）

CP90补齐实际页面：[验收](acceptance/V1.7-checkpoint-90.md)。首次未冻结人工重试前重新GET，版本必须仍等于原草稿、期限按数据库快照判断；未知/到期/已擦除或读取失败不新POST、不自动换版本。已冻结的原version/reason在期限后仍可手动求原回执，服务器已提交则只返回原事实，否则拒绝。清理、技术2xx、人接受与两段coverage继续独立。原九加新三实页/前端132通过，自己的源码CI待验。CP89自身真实MySQL27/V36及十八CI已由[89第六节](acceptance/V1.7-checkpoint-89.md)闭环，旧V35有数据的MySQL升级未验；以下原合同/待验文字按历史范围阅读。

CP89新增保留事实，当前仅本地后台/API验收：[89报告](acceptance/V1.7-checkpoint-89.md)。列表增加retentionEnabled，单通知增加payloadExpiresAt/payloadErasedAt（数据库会话时间）。ONCALL_SWAP_NOTIFICATION_RETENTION_ENABLED默认false、PAYLOAD_RETENTION_DAYS默认30且1–3650；既有事件V36冻结created_at+30日，新事件冻结配置期限，重试不延长。清理仅擦除payload_json，未投递终态标SKIPPED/RETENTION_EXPIRED，原DELIVERED/SKIPPED及回执/审计/重试指纹不删；有效CLAIMED租约暂缓。到期新的POST retry返回409/ONCALL_SWAP_NOTIFICATION_PAYLOAD_EXPIRED，但已提交的同原version/reason回执仍返回当前事实、不重复审计。已擦除载荷不能因关闭清理开关恢复。不是远端撤回或完整PII删除，专门前端呈现及实际MySQL仍待。

当前CP88已闭环：源码7558b9d的[Run37172618761](https://github.com/Trigger726/OnCall-Agent/actions/runs/37172618761)十八作业全部success，见[88第六节](acceptance/V1.7-checkpoint-88.md)/[自身远端proof](assets/v1.7-cp88/remote-proof.json)。九个选定ZIP源HEAD/官方摘要/实际字节及原门禁独立核验；真实MySQL通知18与原换班17零跳过、原生18/兼容99/双JVM六项通过，双时区各451执行/142条件跳过、158新鲜XML，前端129/audit0，Linux新九流程及原14/8/6实页通过，三本轮截图已目视。仅修共用测试的原生CHECK精确断言，生产代码、页面和门禁未改，不制造CP88视觉变化。87首次MySQL失败及旧Demo完整保留；以下按各历史阶段当时范围阅读，不把旧“待验”当本轮终态。

CP87新增通知契约，本地限定通过、实际MySQL/Linux CI待验：[87报告](acceptance/V1.7-checkpoint-87.md)。`GET /{id}/notifications`返回enabled/databaseNow/独立投递facts，无载荷正文/凭证；`POST /{id}/notifications/{notificationId}/retry`显式`version,reason`，管理角色或本次实际参与者才可写。原版本/说明相同的重试回执不重复审计，变更/失效409；不决定换班。默认关闭，启用时申请同事务仅给指定对方入队，决定同事务给双方；稳定键、冻结历史事件、独立worker有限重试/旧lease围栏、无重定向，2xx不等于已读/接受/当前责任。配置前缀`ONCALL_SWAP_NOTIFICATION_`见application.yml，专用HTTPS或localhost/127 HTTP、专用token，URL不可带userInfo/query/fragment；接收端按稳定Idempotency-Key去重，不声称远端exactly-once。旧86等效果和以下当时未完成边界保留。

CP86新增真实页面：本人从未来普通班次选择对方班次，指定对方确认两段责任；按账号保存原键/双版本或决定意图、403/409锁定及手动恢复。当前coverage详情与历史接受分栏，单覆盖取消不复活，未提供成对原子撤销；桌面/手机与旧包效果对照见[86报告](acceptance/V1.7-checkpoint-86.md)。85真实MySQL后端17项限定通过但其整轮CI失败，见[85第五节](acceptance/V1.7-checkpoint-85.md)；86自身4a4141c十八CI/九ZIP及Linux新七流程已闭环，见[86第六节](acceptance/V1.7-checkpoint-86.md)/[远端证据](assets/v1.7-cp86/remote-proof.json)。

两个本人/对方的未来完整普通班次，由本人申请、指定对方确认后一起交换责任。允许同计划或跨计划，保留原普通班次与轮转配置，生成两条临时覆盖；不是先接受两个独立接班请求。CP85首先交付后端，页面由CP86补齐；通知、开放认领、已开始/部分时段互换和成对原子撤销未完成。

## API

所有路径以 `/api/v1/on-call/swaps` 开头，GET需要登录；写入需要运维角色且必须是实际参与者，ADMIN不代替本人同意。

| 方法/路径 | 输入与结果 |
| --- | --- |
| POST / | `firstShiftId, firstVersion, secondShiftId, secondVersion, requestKey, reason`；第一班须属于登录本人，第二班属于指定对方。版本必须显式提供、键为规范UUID，说明1–500字；成功PENDING/v0。 |
| POST /{id}/decisions | `version, status, reason`；对方ACCEPTED或REJECTED，申请人WITHDRAWN。仅PENDING/捕获版本可决定；成功版本+1。 |
| GET / | 可选`scheduleId`匹配任一计划、`scope=ALL/MINE`、`status`；SQL先过滤再取201，返回`databaseNow, requests`最多200条和`truncated`。 |
| GET /{id} | 返回原申请、双方/源班次时段版本、原决定和两条replacementShiftId。ACCEPTED是历史接受事实，不保证两条覆盖仍有效。 |

同申请人原键/相同规范内容可回执，不重建覆盖；同键改内容409 ONCALL_SWAP_KEY_REUSED。同参与者/原版本/同决定理由的重复决定回执，其他终态或版本冲突409 ONCALL_SWAP_VERSION_CONFLICT。缺版本400、未登录401、审计角色写入/非指定参与者403，不存在404。不会将缺失版本默认为0。

## 事务与当前责任

按计划ID升序锁住两条计划，重新读取源，按用户ID升序锁参与者；接受时锁申请行并重查双方最新资格、两计划活跃、两个源普通/未取消/相同快照版本且严格未开始。两个原时段不能存在其他有效普通或覆盖重叠。READ_COMMITTED与规范锁顺序协调已有班次/接班/轮转服务；不引入新的内存锁协调器。

在同一数据库事务内复用原roster.create生成两条覆盖，更新接受事实并记录审计；第二覆盖审计或最终接受审计失败，两个覆盖、决定版本及审计全部回滚。拒绝/撤回不要求无关方仍活跃或源仍有效，以便关闭失效待办。默认不会增加跨计划忙碌人员冲突策略，沿用现有按计划检查。

已有管理班次取消接口可单独取消任一覆盖；再次回执旧ACCEPTED不得复活。当前责任查询以已有 `/api/v1/on-call/coverage` 为准，取消某段后另一段可能仍覆盖，不宣称成对撤销。普通源也可能后来失效，不能仅凭历史状态承诺原负责人必然恢复。

## 对照与验证

运行 `node scripts/verify-oncall-swap-http-ci.cjs` 使用当前实际JAR/独占测试文件库，检查登录/角色/双方确认、幂等与实际coverage、原班次保留及取消后不复活。Windows本地另用 `OPSPILOT_SWAP_UPGRADE=1` 对比保存的CP84/44e旧包：旧接口404，同一自有文件库升级V34后通过；CI明确禁止旧包对照模式替代新包验收。不访问用户文件库、不保存JWT。

H2/MySQL共享17个业务、回滚、并发及HTTP场景。MySQL8.4由Spring管理容器，单独CI作业要求17实际执行零跳过、每项实际JDBC身份/V34迁移及完整池关闭；H2/默认跳过XML不能通过门禁。当前本地证据与未验边界见[85报告](acceptance/V1.7-checkpoint-85.md)。

设计参考[Grafana请求代班与覆盖](https://grafana.com/docs/grafana-cloud/observe-and-act/respond-to-incidents/on-call-schedules/shift-swaps-overrides/)和[PagerDuty排班编辑](https://support.pagerduty.com/main/docs/edit-shift-based-schedules)。Grafana的请求代班不等同本项目两段互换；双向原子交换是本项目路线需求与设计，不冒称厂商实现。
