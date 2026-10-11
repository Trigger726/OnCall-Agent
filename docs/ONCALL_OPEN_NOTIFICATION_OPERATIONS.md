# 开放通知：队列与本进程运行观察

CP124最终追加：[受验578d192自身CI及MySQL闭合](acceptance/V1.7-checkpoint-124.md#cp124自身远端闭合)。主21＋专项12全绿，新9步整包MySQL实际8.4.11、原事件/回执/累计次数及6JVM/端口/容器清理官方包独立核验。页面和观察接口未改，仍无前端写按钮；以下“待验”是保留的阶段时点。

CP124收尾状态：[实际TCP丢响应/重启与限定验收](acceptance/V1.7-checkpoint-124.md)。CP123自己的MySQL共享37及五状态迁移已由官方工件独立闭合；新9步人工重投整包H2通过，同9步MySQL待自己的新CI。生产观察和页面不改，仍无前端重投写按钮；后续不自动扩大功能范围。

CP123新增的是单条FAILED的独立管理写API，并非修改下面全局观察接口：[人工重投契约及验收](acceptance/V1.7-checkpoint-123.md)。POST带通知版本/操作键/理由，只排队原事件；原键回放返回原receipt与独立currentDelivery，权限每次重查，累计次数不清零。页面暂不提供写入口。CP122自己的MySQL整包七步已独立闭合，CP123新42真实MySQL等待自身CI。

管理员只读接口：`GET /api/v1/on-call/open-notification-operations`。不发送通知、不认领、不重投、不改变版本或投递键。HTTP角色与当前数据库ACTIVE/ADMIN双重核对；运维经理、普通值班员、审计员均不能读取这份全局统计，既有单请求技术回执接口不变。

| 范围 | 字段与含义 |
| --- | --- |
| `queue.scope=SHARED_DATABASE_AT_STATEMENT` | 一条SQL、同一数据库事实时间的全局统计，不返回收件人、请求ID、载荷、渠道或令牌 |
| `pending` / `pendingDue` | 全部排队数／已到数据库可尝试时间的数；差值是尚未到期等待，不是已成功发送 |
| `claimed` / `expiredLeases` | 已领取租约／该数据库时间下过期的租约；仍不表示本人已接班或渠道收到 |
| `delivered` / `failed` / `skipped` | 既有技术状态的历史累计行数，不把终态算作当前发送队列 |
| `oldestPendingCreatedAt` | 排队事件的最早创建时间；重试不会换新创建时间。空队列为null，不是浏览器推断 |
| `queue.enabled` | 当前节点配置是否启用投递，不决定历史事件是否存在；关闭不是清空数据库 |
| `worker.scope=THIS_PROCESS_SINCE_START` | 本实例内存观察，与数据库统计不是原子快照，不是集群聚合；重启从零开始 |
| `activeWorkers` / `queuedTicks` | 当前executor近似活跃线程数／本地调度队列长度；固定一worker、SynchronousQueue不保存等待tick |
| `busyRejectedTicks` | executor未关闭期间拒绝接纳的tick数，不是丢失的事件数；后续tick仍从数据库读取 |
| `failedBatches` / `shutdown` | 本进程批次运行异常次数／executor关闭标志；不返回异常细节或把关闭计作忙饱和 |

`databaseNow`与其他数据库时间使用数据库会话本地时间，不擅自附加UTC或转浏览器时区。数据库读取失败返回503/`ONCALL_OPEN_NOTIFICATION_OPERATIONS_UNAVAILABLE`，没有“零积压”或旧缓存回退。非GET请求返回405/`METHOD_NOT_ALLOWED`及Allow响应头，不写业务数据；这一正常客户端错误不应进入意外500日志。

观察动作是人工GET，不增加后台全表轮询；聚合查询仍可能随保留行数增长，实际大规模容量未验收。看到busy计数增加，要结合pendingDue、活跃worker、渠道延迟和失败记录判断，不能只凭累计计数宣称服务当前饱和；disabled、有历史积压和多个节点空闲不等于集群已消费完。该接口不是告警规则、Prometheus exporter、重投按钮或HA调度器。

executor统计及拒绝含义依据[JDK17 ThreadPoolExecutor文档](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html)：统计是本地观察，直接交接队列不能无限堆积，关闭和资源饱和是不同拒绝原因。没有扩大线程池、换收件人或让调用线程发送外部HTTP。

验收与原始证据见[CP120](acceptance/V1.7-checkpoint-120.md)。原始OnCall归档仍为 `archive/oncall-original-2026-08-19`（`0cd654ea49957d05c0e5c683c0e945a5a87c5bab`）；这次补的是OpsPilot真实持久通知队列的运维观察，不冒称原始Agent或厂商完整值班/渠道功能已由本轮实现。

## CP121人工管理员面板

“值班与升级”中仅ADMIN显示“开放通知运行观察”。初始未读不是零，人工点击才GET；错误/非法分区/身份变化/原生跨标签会话变化和卸载均清除旧事实，晚响应受捕获账号/token/epoch围栏约束，不自动重投、轮询或认领。配置关闭仍能看到历史积压，数据库与本实例卡片各自标注范围；shutdown只表示executor已请求关闭，不等于已经终止。

[CP121](acceptance/V1.7-checkpoint-121.md)用真实API发布→阻塞认证HTTP→503后未来重试→关闭配置重启证明上述状态，保留新旧桌面/手机截图。自己的Linux UI待验；父CP120主21/专项10已终态成功且新MySQL7官方工件独立核验，但不是本轮MySQL UI或全渠道验收。

CP122追加：[CP121自身主21/专项11及Linux12官方UI工件已独立闭合](acceptance/V1.7-checkpoint-121.md#cp121自身远端闭合cp122追加2026-10-11)。新的[完整JAR MySQL升级/通知重启专项](acceptance/V1.7-checkpoint-122.md)仍待自己的实际MySQLCI，不能以页面的H2库或原共享MySQL服务测试代替。观察接口、人工只读边界和生产资源不改，旧Demo保留。
