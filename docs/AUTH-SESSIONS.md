# 账号改密与全部会话撤销（CP65服务端、CP66本人页面）

## 升级与安全边界

V31新增sys_user.auth_version，旧账号密码/角色/状态不变。JWT必须包含匹配当前数据库版本的精确非负整数sv，旧无sv Token需重新登录。升级前安排重新登录窗口，全节点升级后才开放改密/撤销；不可混用不校验sv的旧节点。

65只提供本人API；66新增/account/security本人页面。67的原流身份围栏/旧401不清新登录已经获得3b6247b自身十五远端作业/十三实页脚本证据；68的助手在途回答/流式有界队列与预算已有40e0c2f自身十五作业、真实HTTP适配器和MySQL88一般回归/五池关闭证明。69补最后提交/同步返回授权空窗，自身45247a8远端认证/助手HTTP通过，但MySQL93中CHECK异常分类fixture失败，不能标完整通过；70修正精确SQL错误断言并统一同步容量/预算，自身5cae132远端93已验。71后端可选持久化幂等也有bd508bd自身十五作业/MySQL96/Linux三JVM工件，72继续扩展排队和显式取消，本轮新MySQL99需独立验收。不是管理员替别人改密或单设备会话管理；普通退出仍只清本地Token，全部会话撤销需在安全页确认。仍不原子撤回所有已发送字节或全部后台功能，不宣称瞬时取消。

调查发送/工具前后核对原uid/username/sv/exp及当前角色；只保存无原Token的授权快照。GET空闲连接按现有补读周期检查（默认5秒），生产异步任务默认1秒资格扫描，拥塞/数据库等待可延长，非跨实例瞬时广播保证。停用/到期/版本不符关闭读取；仍ACTIVE但降为AUDITOR可继续读，不能启动/继续调查。最终报告/提案事务持有账号行锁，在等待后重新核对到期，与账号撤销事务排序。

普通断网不取消任务；全部会话撤销后的原任务安全取消、下一次原Token请求401。生产任务排队项及时移除，正在进行的Provider使用连接/读取预算和持久化取消状态协作式停机，不强制interrupt冷JAR HTTP类加载，不能承诺即时回收正在使用的工作线程。当前授权快照在内存，崩溃后不重新执行原任务，只由既有逾期恢复结算孤儿；未来若实现任务恢复执行，须另外持久化/复核原授权。

助手准备/完成事务按账号→会话行锁排序，外部模型不持有这些行锁；已接受USER保留，撤销/到期/清空后的晚回答不补写。回答/标题/完成审计一起提交，最后仍检查原lease有效期；同步返回前另检查原lease。若回答在有效授权时已经完成、之后才撤销，不删除已合法提交的历史，只拒绝晚HTTP正文；新登录仍可正常读取历史。这些授权边界不承诺每个网络字节与撤销原子化。详情和首次失败见[68](acceptance/V1.7-checkpoint-68.md)/[69](acceptance/V1.7-checkpoint-69.md)。助手允许所有有效角色读取/回答，不把调查的ADMIN/OPS_MANAGER/ON_CALL门槛错误套给AUDITOR对话。

助手同步/messages和流式/stream在同一实例内共享ASSISTANT_WORKERS=4、ASSISTANT_QUEUE_CAPACITY=16、ASSISTANT_EXECUTION_TIMEOUT=60s（含排队），不同节点不是全局一个池。先验证会话归属，队满503 ASSISTANT_QUEUE_SATURATED且该问题零消息/请求写入；同步通过DeferredResult返回原ApiResponse JSON，预算到期504 ASSISTANT_EXECUTION_TIMEOUT，撤销/到期401。运行中已接受USER保留，排队超时则USER/ASSISTANT/完成审计均零并释放队列，72为已接纳的键保留TIMED_OUT墓碑；不自动重试同一问题。MVC禁用独立等待计时器，统一依现有执行器预算/资格扫描停止。资格检查默认1秒轮询，数据库/调度拥塞可延长，非关闭SLA。流式超时仅原授权仍有效才发固定error/无done，撤销不发正文；模型I/O仍占用worker直到自身返回。详见[70](acceptance/V1.7-checkpoint-70.md)。跨节点矩阵继续，不将规则降级或本地受控模型当生产质量保证。

71增加可选助手Idempotency-Key契约（不等于Agent调查的运行键）：同步/messages与流式/stream使用同一个键，首尾空白去除后为1至128个ASCII字母、数字或._:-，大小写敏感并按会话隔离；数据库仅保存键与trim后问题的SHA256。已接受USER与RUNNING同事务，回答/标题/完成审计与COMPLETED同事务。完成后同键同问题返回原Message ID和原证据，响应头X-OpsPilot-Idempotent-Replay:true，不占模型队列；不同问题409 ASSISTANT_IDEMPOTENCY_CONFLICT，进行中409 ASSISTANT_REQUEST_IN_PROGRESS，终态409不自动重跑。完成重放仍检查原HTTP lease；清空将键改为SUPERSEDED并置空消息引用，不使旧键重新执行，删除会话才级联删除请求。

GET /api/v1/assistant/sessions/{id}/request需相同Idempotency-Key头，缺失/非法400，另一账号404；本人读取id/status/questionMessageId/answerMessageId/deadlineEpochMs。71只持久化RUNNING以后，72增加QUEUED/CANCELLED：实际有界容量接纳后，以原HTTP lease同事务写QUEUED，不写USER，worker等待接纳事务落定；开始准备再把QUEUED→RUNNING与USER原子提交。接纳失败在释放worker门前设置停止围栏，Future.cancel(false)本身不能保证已运行Callable不执行。重启后QUEUED/RUNNING都按持久化epoch预算结算超时，预算前同键409、不自动迁移模型。状态404仍可能是接纳事务尚未提交，客户端不得据此换键自动重发。不带键保留旧行为，默认页面尚未生成稳定键或冻结恢复，因此不能声称所有页面对话已幂等。证据见[71](acceptance/V1.7-checkpoint-71.md)/[72](acceptance/V1.7-checkpoint-72.md)。

POST /api/v1/assistant/sessions/{id}/request/cancel无需正文，以原Idempotency-Key和当前本人凭证取消该请求，缺失/坏键400、不存在/其他账号404。账号→会话→请求行锁下只把QUEUED/RUNNING改为CANCELLED并同事务写一次ASSISTANT_REQUEST_CANCEL审计；重复调用回当前状态，不重复审计。完成、超时、撤销或清空先提交时保持原终态，不把合法历史答案改成取消。取消先提交则原完成事务不能提交答案/标题/完成审计；排队取消零USER且腾槽，同步原HTTP409 ASSISTANT_REQUEST_CANCELLED，流式发cancelled事件/无done（仅原lease仍有效）。本机提交后立即通知，周期/worker也检查持久化状态；外部模型自身返回前仍可能占用worker，不强制中断，不承诺每个网络字节与取消原子化。默认页面的显式取消按钮/稳定键与冻结恢复、后台主动回收、跨节点时钟和通知矩阵仍待验。

客户端流在建立时冻结原Token，仅留内存；本标签登录/退出事件和跨标签storage事件使旧连接中止，响应、每次read、事件回调及重连检查Token是否仍相同，旧401不踢新会话。流终止不向后台发送取消；真正会话撤销由后端处理。浏览器Token围栏不是JWT认证，也不宣称旧标签导航栏瞬时同步或已经显示的历史内容立即擦除；刷新后由auth/me取得当前身份。Agent恢复键按uid/username/incident命名，不含Token/口令。同一账号歧义请求重新登录后继续同键；另一账号不继承；无归属旧键保留但不自动迁移。用户信息缺失/坏格式在POST前拒绝，纯GET订阅不依赖这个UI namespace。

复跑`node scripts/verify-oncall-browser-ci.cjs`默认十三脚本，包含三个流式入口。仅本地诊断可设OPSPILOT_STREAM_SESSION_ONLY=1；旧前端实页捕获设OPSPILOT_STREAM_SESSION_BASELINE=1并使用保留target/cp67-after/opspilot-cp67.jar，结果BASELINE_CAPTURED不能当PASS；两种缩小/旧版模式在CI均拒绝。测试只修改runner隔离数据库，绝不在日常演示库上撤销账号。

页面错误当前密码保留有效登录、清空口令输入；成功清理原凭证并保留本人用户名/密码空白，明确重新登录。10秒预算、busy互锁；响应丢失/5xx/错误成功合同均不自动重试、不缓存密码，提示结果不确定并重新登录核对，新密码优先尝试。旧响应不能清理后来新登录的Token。桌面/390px及真实响应丢失对照见[66报告](acceptance/V1.7-checkpoint-66.md)。

## 本人接口

- POST `/api/v1/auth/logout-all`：Bearer认证，无需目标账号/请求正文；只撤销当前本人账号全部已签发会话。
- POST `/api/v1/auth/password`：Bearer认证，JSON字段currentPassword和newPassword；验证当前密码后同事务改哈希、升会话版本并写审计。
- 成功data均含`reauthenticationRequired: true`、`scope: ALL_ISSUED_SESSIONS`，不返回新Token。客户端须清旧凭证再显式登录，不自动重试旧命令。
- 新密码至少15个Unicode码点、最多72个UTF-8字节；拒绝NUL、无效Unicode、全空白、同密码。不强制机械大小写/数字组合。已有短演示密码允许正常登录；不是生产口令推荐。
- 登录和当前密码同样拒绝超过72字节，以免BCrypt截断造成不同输入等价。大于72个Java字符的DTO可返回400，合法字符长度但UTF-8超限登录返回401；不要把所有超限输入都承诺为同一错误码。
- 被撤销Token下一次受保护请求401 AUTHENTICATION_REQUIRED；版本耗尽409 AUTH_VERSION_EXHAUSTED；竞争的旧会话命令401。普通权限不足仍403。

直接SQL改password_hash不等同此API，必须同步递增auth_version；不能恢复旧版本、重用身份或把数据库回滚称为撤销保证。审计不包含口令/Token，保留真实操作者与HTTP来源IP。泄露密码blocklist/MFA/节流/Argon2迁移和生产TLS仍待完成，72字节也并非支持任意64字符Unicode口令。

## 文件库与演示复跑

真实默认H2文件URL已使用WRITE_DELAY=0。自定义H2 DB_URL也应显式包含该参数；MySQL使用自身持久化配置，不追加H2参数。不要直接在日常演示账号上运行验收改密，避免影响历史Demo；先备份并用独立文件库。同步写成本/断电/损坏卷尚未做生产认证。

构建后运行`node scripts/verify-auth-session-ci.cjs`。它仅使用空闲回环9933/9934和自己新建的target/auth-session-it/run-*文件库；不停止其他监听者，不修改日常文件库。同文件库不同JVM检查旧Token拒绝、当前Token正对照、新旧密码、审计/时间线不变，最后关闭自己创建的进程。

助手真实适配器复跑`node scripts/verify-assistant-session-ci.cjs`：只用空闲9937/9938、自有内存库与受控loopback DashScope HTTP，不接生产模型/真实密钥；先正常正文正对照，再真实logout/清空/流式超时，混合同步/流式队满与同步运行/排队超时，后继真实回答证明旧worker实际退出。旧包target/cp68-before/opspilot-cp67-client.jar配OPSPILOT_ASSISTANT_SESSION_BASELINE=1记录历史撤销反例；69旧包target/cp70-before/opspilot-cp69.jar配OPSPILOT_ASSISTANT_BUDGET_BASELINE=1记录预算缺口。两模式不能同时设置、仅BASELINE_CAPTURED，CI在启动Java前拒绝。69的行锁/实际审计INSERT失败/完成中到期矩阵在H2与既有真实MySQL兼容测试中复跑，不靠新增生产测试端点。

持久化幂等另跑`node scripts/verify-assistant-idempotency-ci.cjs`：空闲9941/9942、自有H2文件库WRITE_DELAY=0、三个独立JVM与受控生产HTTP适配器，relay先收到真实后端200再截断客户端响应；新JVM同键回原答案/一次模型调用，运行超时不晚写，SIGKILL后按原预算结算且不重跑。旧70包配OPSPILOT_ASSISTANT_IDEMPOTENCY_BASELINE=1仅记录丢响应/重启重复反例（未测旧SIGKILL），CI在启动Java前拒绝旧模式。仅上传脱敏JSON/日志，不上传文件数据库或JWT。

显式取消另跑`node scripts/verify-assistant-cancel-ci.cjs`：空闲9943/9944，自有文件库与受控模型HTTP，真实丢取消200响应后同键核对只一次审计；排队取消腾槽/同步和流式取消无晚回答，模型释放后的新回答证明实际退出；QUEUED时SIGKILL、新JVM按原预算结算无USER，取消事实和一次审计仍在。若本地存在保留target/cp72-before/opspilot-cp71.jar，会先用旧71包完成一个键，再以新包同库V32→V33核对原答案/请求保持且无模型重跑；CI没有旧包时不冒充升级夹具已执行。OPSPILOT_ASSISTANT_CANCEL_BASELINE=1仅捕获旧包缺口，CI启动Java前拒绝；新旧夹具不触及日常数据库。

独立旧JAR放在target/cp65-before/opspilot-cp64.jar，OPSPILOT_AUTH_SESSION_BASELINE=1只记录旧接口404与旧Token仍可读，输出BASELINE_CAPTURED，绝不是PASS；CI禁止该模式。口令/Token仅驻留验证进程内存，上传证据限JSON/日志，不上传数据库。原始失败和新旧对照见[65报告](acceptance/V1.7-checkpoint-65.md)。
