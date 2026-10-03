# 账号改密与全部会话撤销（CP65服务端、CP66本人页面）

## 升级与安全边界

V31新增sys_user.auth_version，旧账号密码/角色/状态不变。JWT必须包含匹配当前数据库版本的精确非负整数sv，旧无sv Token需重新登录。升级前安排重新登录窗口，全节点升级后才开放改密/撤销；不可混用不校验sv的旧节点。

65只提供本人API；66新增/account/security本人页面，67后端c903d59已获十五远端作业/真实MySQL88/同库跨JVM及工件摘要证明。本轮客户端进一步隔离原流身份，Incident/助手调查/助手对话旧401不清新登录，84单测/十三实页脚本和桌面/390px对照已验，新客户端远端待验。不是管理员替别人改密或单设备会话管理；普通退出仍只清本地Token，全部会话撤销需在安全页确认。仍不原子撤回全部已鉴权在途请求、已发送字节或所有后台功能，不宣称全部工作立即取消。

调查发送/工具前后核对原uid/username/sv/exp及当前角色；只保存无原Token的授权快照。GET空闲连接按现有补读周期检查（默认5秒），生产异步任务默认1秒资格扫描，拥塞/数据库等待可延长，非跨实例瞬时广播保证。停用/到期/版本不符关闭读取；仍ACTIVE但降为AUDITOR可继续读，不能启动/继续调查。最终报告/提案事务持有账号行锁，在等待后重新核对到期，与账号撤销事务排序。

普通断网不取消任务；全部会话撤销后的原任务安全取消、下一次原Token请求401。生产任务排队项及时移除，正在进行的Provider使用连接/读取预算和持久化取消状态协作式停机，不强制interrupt冷JAR HTTP类加载，不能承诺即时回收正在使用的工作线程。当前授权快照在内存，崩溃后不重新执行原任务，只由既有逾期恢复结算孤儿；未来若实现任务恢复执行，须另外持久化/复核原授权。助手服务端其他在途功能仍需单独验收，见[67阶段报告](acceptance/V1.7-checkpoint-67.md)。

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

独立旧JAR放在target/cp65-before/opspilot-cp64.jar，OPSPILOT_AUTH_SESSION_BASELINE=1只记录旧接口404与旧Token仍可读，输出BASELINE_CAPTURED，绝不是PASS；CI禁止该模式。口令/Token仅驻留验证进程内存，上传证据限JSON/日志，不上传数据库。原始失败和新旧对照见[65报告](acceptance/V1.7-checkpoint-65.md)。
