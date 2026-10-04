# 双向换班成对撤销契约（CP92 后台 / CP94 页面）

最新[CP97](acceptance/V1.7-checkpoint-97.md)新增独立开放认领后台；不改变成对撤销契约。CP96自己的[Run37207086248](https://github.com/Trigger726/OnCall-Agent/actions/runs/37207086248)十八success，原成对14/HTTP3与五ZIP已独立核验，见[96第六节](acceptance/V1.7-checkpoint-96.md#6-自己源码远端限定通过)。此前failure保留，超时根因未证实；该证据不替代新V38/MySQL24/新页面验收，以下按历史范围阅读。

最新状态：[CP96](acceptance/V1.7-checkpoint-96.md)仅测试/CI观测改动，两次原14和Node128本地通过，自身远端待验。CP94后续afd5cfb十八CI/成对14已限定通过，见[94第六节](acceptance/V1.7-checkpoint-94.md#6-后续文档基线自身远端限定通过)；CP95自己的148f6c9/[Run37205769613](https://github.com/Trigger726/OnCall-Agent/actions/runs/37205769613)则16success/浏览器failure/镜像skipped，第6流程中断后的读取等待超时。通知12/HTTP3/MySQL19等限定成功不覆盖该新failure；历史失败/旧Demo保留，根因未证实，生产成对契约不改。以下“当前/待验”按原历史时点阅读。

保留原ACCEPTED历史，只撤销本次接受生成的两条覆盖。一次数据库事务提交两条取消、独立撤销台账和三条审计；不等于发出两个单独取消请求。后台见[验收92](acceptance/V1.7-checkpoint-92.md)，专用页面已在[CP94](acceptance/V1.7-checkpoint-94.md)本地限定通过：最终包14新实页与原12/14/8/6、前端147均通过，自己的源码CI/真实MySQL待验。

当前远端追加状态见[CP94第五节](acceptance/V1.7-checkpoint-94.md#5-自己源码首次远端失败原记录保留未闭环)：bf1009c的真实MySQL19及原17/27/1/99独立重放通过，但整轮CI failure；旧通知第4流程刷新等待超时，新成对HTTP3/UI14步骤跳过。自身前端147/audit0和双时区回归限定通过，不把本地通过升级为Linux页面已验，原失败记录保留，先诊断再闭环。

实际CP92自己的MySQL19有一项时间夹具断言失败，整轮CI仍为failure；[CP93第四节](acceptance/V1.7-checkpoint-93.md#4-自己源码远端闭环页面范围仍待交付)已由自己033f491十八CI/真实MySQL19和两次实际落库时间屏障闭合，强化门禁独立重放通过，生产契约/DDL未改。CP94页面首次POST前再GET捕获的两ID/三版本，按数据库快照判断期限；未知/失败/过期/任一取消零POST。按账号会话存储后才提交；冻结后只手动原键回执，403/409持久锁定，同账号换token也围栏晚200。新旧七图及首次失败保留；不把CP93CI借给CP94。

## API 与明确意图

路径前缀 `/api/v1/on-call/swaps`。`GET /{id}/coverage`需登录，以单条SQL快照返回`databaseNow, accepted, firstReplacement, secondReplacement, revocation`；未接受请求的两条replacement可为null，字段名accepted不意味着其status已经接受。历史接受与当前覆盖/撤销分开显示。

`POST /{id}/coverage/revoke`仅当前活跃ADMIN/OPS_MANAGER。正文五项全部必需，不将缺失版本默认为0：

```json
{
  "swapVersion": 1,
  "firstReplacementVersion": 0,
  "secondReplacementVersion": 0,
  "operationKey": "2757c5b4-aa17-4f08-8368-4e8294e13628",
  "reason": "双方协商恢复常规排班"
}
```

版本来自原已接受换班和最新两条覆盖，不自动换成409后的新版本；operationKey为小写规范UUID，reason输入1–500字且不能全空白，保存时去首尾空白。成功返回同一CoverageView，原换班status/version/参与者决定/两条ID不变；两覆盖版本各+1，台账保存管理账号、原键、三个捕获版本、理由和微秒撤销时间。

同一管理账号原键/相同内容可手动求回执，结束后也可，只返回已提交事实；不再取消、不新审计、不创建覆盖。原回执仍重查当前管理资格。另键、另管理账号不是同一次意图；原已提交换班申请/接受回执也不会复活取消的覆盖。

| 响应 | 含义及处理 |
| --- | --- |
| 400 | 缺失/null/负版本、非规范键或无效说明；尚未执行撤销。 |
| 401/403 | 未认证或当前非管理角色；不得通过参与者身份代替管理资格。 |
| 404 | 请求/计划/覆盖不存在，先核对事实。 |
| 409 `ONCALL_SWAP_VERSION_CONFLICT` | 不是原已接受版本，或两条原覆盖ID不全。 |
| 409 `ONCALL_SWAP_SOURCE_CHANGED` | 覆盖负责人/计划/时段或override性质与接受快照不符。 |
| 409 `ONCALL_SHIFT_VERSION_CONFLICT` | 任一已独立取消、版本变化或版本已到上限；另一条不被顺手取消。 |
| 409 `ONCALL_SWAP_COVERAGE_EXPIRED` | 任一已结束；不撤销过去责任。 |
| 409 `ONCALL_SWAP_REVOCATION_KEY_REUSED` / `ONCALL_SWAP_COVERAGE_ALREADY_REVOKED` | 原键改内容/跨请求复用，或另一意图试图重复撤销；不自动重基。 |

## 事务与边界

READ_COMMITTED；计划ID升序→请求行→当前管理账号行锁，随后重查两条覆盖和数据库时间。与既有单段cancel使用同一计划锁协调；已经开始但都未结束也可撤销剩余覆盖，任一变化/结束就整体拒绝。第二条取消审计或最终台账审计失败，两取消、台账和新审计一起回滚。等待锁之后及最后审计工作之后都重新核对数据库时间，不声称与物理commit瞬间精确原子化的截止SLA。

V37仅增加独立oncall_swap_revocation表；一换班一台账、账号+键唯一、请求/账号外键及非负版本CHECK，不修改旧V34–V36 checksum或旧通知。原普通班次已取消、人员不合格或计划已停用时，清理覆盖不保证原人责任恢复；以`/api/v1/on-call/coverage`实际结果为准，可能存在空档。排班过去时段、既发HTTP、历史技术投递不能撤回；本阶段不增加撤销外部通知，也不承诺所有在途普通HTTP与会话撤销瞬间原子化。

## 可复跑验证与旧 Demo

H2/MySQL共享19个正常、拒绝、真实数据库时间、回滚、并发与HTTP角色场景。MySQL必须显式`-Dopspilot.oncall.swap.revocation.mysql.enabled=true -Dtest=MySqlOnCallSwapRevocationIntegrationTest`，然后`node scripts/verify-oncall-swap-revocation-mysql.cjs`要求19项零跳过、每项实际MySQL8.4/独占schema/V37、完整Maven及有序池关闭；合成门禁测试不是数据库验收。

生产JAR真实HTTP：`node scripts/verify-oncall-swap-revocation-http-ci.cjs`，新独占文件库、固定端口9978/9979仅空闲才用，JVM重启后核对同一原键/事实和三审计。本地`OPSPILOT_SWAP_REVOCATION_UPGRADE=1`另启保存的CP91包，同一owned库实际V36→V37：旧成对接口404/独立取消只能一段，新API不把旧半取消伪造成新撤销。CI禁止对照模式替代本源码验收。通知入队开启、发送首次延迟24小时，只验证非空三条历史不变，不连接真实账户或冒充外部投递。响应丢失是已收到200响应头但未读JSON正文即真实销毁socket，非前端已实现的断网恢复演示。

页面真实浏览器：`node scripts/verify-oncall-swap-revocation-ui-ci.cjs`，自有9972/9973/9974、独占文件库、14项角色/存储/刷新/丢响应/409/403/晚200围栏。使用route.fetch实际提交200后中断浏览器响应，并非伪造成功JSON；当前角色/过期仅用owned H2测试JDBC夹具，不当MySQL证明。原区间coverage仍负责当前责任查询；撤销不是原班次/原人一定恢复。

所有旧报告/首次错误/JAR/截图保留。当前本地状态以94追加节、各轮自己的远端状态以对应报告为准；整个项目仍继续，不将页面限定通过叫100%。
