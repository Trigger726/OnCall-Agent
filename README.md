# OpsPilot

开发中 CP110：[非空旧 V38→V39 升级与原 HTTP 回归](docs/acceptance/V1.7-checkpoint-110.md)。CP109 自身实际 MySQL22/0跳过已[限定闭合](docs/assets/v1.7-cp109/remote-proof.json)；本轮本地升级7/原HTTP8/原升级10/选定后端46/Node184通过。新源码实际MySQL升级、完整原CI与成员页面/新旧Demo仍待，独立WIP不合入已验收分支，所有未提交改动保留。下方状态按历史时点阅读。

当前 CP107：[完整取消帧门禁与成员契约](docs/acceptance/V1.7-checkpoint-107.md)。CP106自身[21作业 success/两个官方ZIP的191检查证据](docs/assets/v1.7-cp106/remote-close-proof.json)已闭合：Linux原生六项/两流完整取消JSON与EOF、八认证runner及后续通知12/成对14/开放33均执行。CP105首次失败仍保留，不推断唯一根因。本轮加强测试而非改生产UI；[计划成员权限](docs/ONCALL_PLAN_MEMBERSHIP_DESIGN.md)仍是设计，未实现。以下历史待验按各检查点当时范围阅读，整体active。

最新 CP106：[真实升级闭环与两处首失败](docs/acceptance/V1.7-checkpoint-106.md)/[58检查本地证据](docs/assets/v1.7-cp106/local-proof.json)。CP105自身真实MySQL五键探针/旧新JAR升级10由[274检查七ZIP证据](docs/assets/v1.7-cp105/remote-first-proof.json)限定通过；整轮18成功/2失败/1跳过，认证打包依赖解析失败、原生第六页读取超时保持原证据。只补认证构建缺失release检查/完整日志及页面测试读流观测，Node161、本机原六流程前后通过；自己新CI和Linux失败根因仍待，生产代码/DDL/页面未改，历史Demo保留，整体active。

最新 CP105：[MySQL夹具精确键修复与首失败保留](docs/acceptance/V1.7-checkpoint-105.md)/[121检查本地证据](docs/assets/v1.7-cp105/local-proof.json)。Node159、默认后端518执行/196条件跳过、H2旧库升级10及默认HTTP8通过；修复后的真实MySQL需本次提交自身CI验证。CP104自己CI终态19成功/1升级失败/1跳过，七ZIP由[323检查证据](docs/assets/v1.7-cp104/remote-first-proof.json)核验，不能标整轮全绿。生产代码/DDL/页面未改，历史Demo和失败保留，整体迭代继续。

最新 CP104：[旧包有数据 MySQL 升级门禁](docs/acceptance/V1.7-checkpoint-104.md)/[128检查本地证据](docs/assets/v1.7-cp104/local-proof.json)。真实旧/新JAR、非空指定/双向撤销历史、迁移校验和和重启原键恢复已接入独立CI；增强H2升级10/默认HTTP8、Node158和默认后端518执行/196条件跳过通过，新MySQL本地跳过、自己CI待验。CP103自己20CI与六ZIP已[限定核验](docs/acceptance/V1.7-checkpoint-103.md#7-自身远端限定闭合cp104追加)。生产页面未改，历史Demo/首次失败保留，整体active。

最新 CP103：[SSE预览与上下文就绪修复](docs/acceptance/V1.7-checkpoint-103.md)/[106检查本地证据](docs/assets/v1.7-cp103/local-proof.json)。真实503 JSON与原键恢复保留，原生6、助手原8+新1、开放33、成对14、通知12、原14runner、默认后端518执行/195条件跳过、前端175/Node148及audit0本地通过。CP101自己20CI与三个ZIP已[限定核验](docs/acceptance/V1.7-checkpoint-101.md#6-自己源码远端的限定核验cp103追加)；CP103自己远端仍待。新旧Demo和首次失败完整保留，不等于整体100%。

最新 CP101：[报告](docs/acceptance/V1.7-checkpoint-101.md)/[证据](docs/assets/v1.7-cp101/local-proof.json)。回执实际归属、可靠清除和跨标签页身份围栏：开放接班33、原14/成对14/通知12/助手8、前端174/脚本148与audit0本地通过；原生助手预览回归失败保留，新源码自身CI待验，不能称整体验收完成。CP100自身[首次远端失败](docs/assets/v1.7-cp100/first-remote-proof.json)保留；旧Demo及完整路线继续。

最新 CP100：[真实资格恢复与存储故障追加](docs/acceptance/V1.7-checkpoint-100.md)/[本地proof](docs/assets/v1.7-cp100/local-proof.json)：原16+新8共24实页，原JWT实际403、恢复角色/三次JAR后仍锁定；撤回quota、静默保存、读回/清除/锁定写失败及发布/撤回GET500已验，前端167/Node146。只增强测试与证据，生产页面/DDL未改、旧Demo保留；自己的Linux24与剩余身份/存储矩阵待。CP99自身Linux16/MySQL24/前端167由[99追加](docs/acceptance/V1.7-checkpoint-99.md#6-自己源码的-linux-页面与-mysql-限定闭合)限定闭合；完整目标继续，以下按历史范围阅读。

最新 CP99：[专用开放接班页面与新旧Demo](docs/acceptance/V1.7-checkpoint-99.md)/[本地proof](docs/assets/v1.7-cp99/local-proof.json)，本人发布、认领、撤回、原键手动回执及三种晚200围栏已有16实页流程；前端167/Node145/build/audit0通过。旧包/失败/八张新旧图保留，自己源码Linux与新页面资格403等完整矩阵待验。CP98自身20CI及独立HTTP8由[98追加](docs/acceptance/V1.7-checkpoint-98.md#6-自己源码的linux-http限定闭合)限定闭合。以下按历史时点阅读，完整目标继续。

最新 CP98：[开放认领真实网络/升级/重启](docs/acceptance/V1.7-checkpoint-98.md)/[本地证据](docs/assets/v1.7-cp98/local-proof.json)，旧V37→V38升级10、新版独立8和Node142通过；旧包/台账与三次夹具失败保留。新HTTP runner自己的Linux CI、专用页面和桌面手机新旧Demo待验。前一CP97自己的[19作业CI](https://github.com/Trigger726/OnCall-Agent/actions/runs/37209488300)已成功，新MySQL24及原门禁独立重放，见[97第六节](docs/acceptance/V1.7-checkpoint-97.md#6-自己源码实际mysql与远端限定闭合)/[284记录远端证据](docs/assets/v1.7-cp97/remote-proof.json)。以下按历史时点阅读，完整目标继续。

最新 CP97：[开放认领后台](docs/acceptance/V1.7-checkpoint-97.md)/[本地证据](docs/assets/v1.7-cp97/local-proof.json)，独立V38、本人自主认领、唯一赢家与同事务回滚；24项H2及Node136通过。真实MySQL24、新生产JAR网络/升级重启、专用页面与新旧Demo待验，不称完整功能完成。CP96自己的[18作业CI](https://github.com/Trigger726/OnCall-Agent/actions/runs/37207086248)已成功，见[96第六节](docs/acceptance/V1.7-checkpoint-96.md#6-自己源码远端限定通过)/[263记录远端证据](docs/assets/v1.7-cp96/remote-proof.json)，不替代V38。以下状态按历史时点阅读，完整目标继续。

最新 CP96：[成对中断拦截与请求证据](docs/acceptance/V1.7-checkpoint-96.md)/[本地proof](docs/assets/v1.7-cp96/local-proof.json)，Node128、两次完整原14页面限定通过；仅测试/观测变化，生产页面和预算未改，新源码自身CI待验。前一148f6c9自己的[Run37205769613](https://github.com/Trigger726/OnCall-Agent/actions/runs/37205769613)为16success/浏览器failure/镜像skipped，[250记录失败proof](docs/assets/v1.7-cp95/first-remote-proof.json)保全；通知12/HTTP3/原14/8/6和MySQL19/17/27/1/99限定成功不升级为整轮全绿。超时根因未证实，[开放认领仍是待实现设计](docs/ONCALL_OPEN_CLAIM_DESIGN.md)，完整目标继续。

最新 CP95：[通知拦截生命周期与有限诊断](docs/acceptance/V1.7-checkpoint-95.md)，本地 Node121/前端147、原通知12/成对页面14/HTTP3限定通过，[本地证据](docs/assets/v1.7-cp95/local-proof.json)。前一源码 afd5cfb 的 [Run37204083856](https://github.com/Trigger726/OnCall-Agent/actions/runs/37204083856)实际18作业success，见[第二次远端证据](docs/assets/v1.7-cp94/second-remote-proof.json)。当前新脚本自己的CI待验；首次远端失败保留、超时根因未证实，生产页面/预算未改，完整路线继续。以下“待验/当前”均按历史时点阅读。

最新 CP94：[成对撤销页面与原意图围栏](docs/acceptance/V1.7-checkpoint-94.md)本地限定通过，见[275记录证据与七张新旧图](docs/assets/v1.7-cp94/local-proof.json)。bf1009c自己的[Run37203313914](https://github.com/Trigger726/OnCall-Agent/actions/runs/37203313914)首次failure：16success/浏览器failure/镜像skipped，见[237记录失败proof](docs/assets/v1.7-cp94/first-remote-proof.json)。真实MySQL19及原17/27/1/99独立重放相等、双时区480执行/171跳过/83XML、前端147/audit0、Linux旧14/8/6限定通过；旧通知第4流程错误后刷新超时，新的成对HTTP3/UI14未执行，仍须诊断闭环。本地最终新14、原12/14/8/6及Node117通过不替代新源码远端。完整路线/目标active，首次失败/旧Demo保留；下方文字按历史范围阅读。

最新 CP93：[持久化时间边界夹具/门禁修复](docs/acceptance/V1.7-checkpoint-93.md)已由自身033f491/[Run37199872957](https://github.com/Trigger726/OnCall-Agent/actions/runs/37199872957)十八CI success闭合，见[五ZIP自身proof](docs/assets/v1.7-cp93/remote-proof.json)。真实MySQL19零跳过及两次.900→实际下一整秒/DB释放超过存储截止，独立强化门禁重放与CI保存结果相等；原17/27/1/99、双时区各480执行/171跳过/83新鲜XML、前端132/audit0及Linux原12/14/8/6与HTTP三流程也核对。脚本115为本地结果。生产代码/DDL/页面未改，没有新视觉Demo；CP92自己的[首次失败](docs/assets/v1.7-cp92/first-remote-proof.json)保留，不改写绿灯。专用撤销页面、冻结原键/三个版本、真实浏览器丢响应/409/身份围栏和桌面手机新旧对照继续必需，完整路线/目标active。

企业级智能运维与故障闭环平台，面向能源企业信息系统的告警治理、Incident 协同、值班升级和证据驱动调查。

OpsPilot 不是“输入一条告警让大模型猜根因”的聊天演示。它先把资源、告警、事故、人员、变更和审计放进同一个业务闭环，再将 AI 限制在可引用、可追踪的证据上下文中。

## 核心能力

最新 CP90：[保留事实页面验收](docs/acceptance/V1.7-checkpoint-90.md)/[本地机器证据](docs/assets/v1.7-cp90/local-proof.json)。数据库快照/冻结期限/清理记录可见；首次新重试前重新GET，到期或读取失败0POST，原已提交意图在载荷擦除后仍只手动求原回执。前端132、脚本92、真实生产JAR通知12流程/桌面与390px通过，六张新旧图保留；自己的源码CI待验。CP89源码adc89b3的[十八作业CI](https://github.com/Trigger726/OnCall-Agent/actions/runs/37174799138)已全部成功，五选定ZIP与真实MySQL通知27/原换班17、一般99独立重放通过，见[89远端证据](docs/assets/v1.7-cp89/remote-proof.json)。旧V35有数据的真实MySQL升级仍未验，整体路线继续。

以下为各检查点历史记录，“当前/待验”按当时范围阅读，以最新报告的追加闭环为准。

当前推进CP89通知载荷保留：[89报告](docs/acceptance/V1.7-checkpoint-89.md)/[本地证据](docs/assets/v1.7-cp89/local-proof.json)。V36冻结期限、显式清理开关默认关闭、有界清理与租约/领取/人工重试围栏；只擦除payload，保留业务、技术回执和原重试指纹。原18+新9通知/换班17、双时区各461执行、前端129/Node91本地通过；自身真实MySQL与专用前端呈现待验，不借以下88绿灯替代。旧Demo保留；本轮误用clean及逐摘要恢复范围在报告中公开记录。

当前CP88已闭环：源码7558b9d的[Run37172618761](https://github.com/Trigger726/OnCall-Agent/actions/runs/37172618761)十八作业全部success，见[88第六节](docs/acceptance/V1.7-checkpoint-88.md)/[自身远端proof](docs/assets/v1.7-cp88/remote-proof.json)。九个选定ZIP源HEAD/官方摘要/实际字节及原门禁独立核验；真实MySQL通知18与原换班17零跳过、原生18/兼容99/双JVM六项通过，双时区各451执行/142条件跳过、158新鲜XML，前端129/audit0，Linux新九流程及原14/8/6实页通过，三本轮截图已目视。仅修共用测试的原生CHECK精确断言，生产代码、页面和门禁未改，不制造CP88视觉变化。87首次MySQL失败及旧Demo完整保留；以下按各历史阶段当时范围阅读，不把旧“待验”当本轮终态。

当前CP87新增双向换班持久化通知与独立技术投递页面，本地限定通过、自己的源码CI/MySQL仍待：[验收87](docs/acceptance/V1.7-checkpoint-87.md)/[机器证据与新旧图](docs/assets/v1.7-cp87/local-proof.json)。申请通知指定对方、决定通知双方；稳定键/冻结事件同事务入队，独立有界worker、租约围栏/有限重试，技术2xx不等于人已读或接受。新18项、双时区各451实际执行、通知九实页/原14+8+6、前端129/Node113/audit0通过，旧86包与两次首次失败保留。文档基线bccd96a的tracing下载网络失败不称全绿；以下86等为各自历史源证据，不能替代87待验。完整路线继续。

最新86自身4a4141c/[Run37168789059](https://github.com/Trigger726/OnCall-Agent/actions/runs/37168789059)十八作业success，九ZIP源HEAD/实际摘要与独立重放已核验，见[86第六节](docs/acceptance/V1.7-checkpoint-86.md)/[远端证据](docs/assets/v1.7-cp86/remote-proof.json)。真实MySQL8.4.11换班17/原生18/原99及双JVM六项、双时区各433实际执行/154新鲜XML、Linux十四/八/六实页和新换班七流程通过；前端120/全树审计0，Node102为本地结果。已目视本次远端双方确认/当前覆盖及390px冻结恢复/独立取消四图；旧Demo、本地首次失败与85整轮failure保留。通知/开放认领/部分班次/成对撤销/DST及整体路线继续。

85自身332211e/[Run37156379925](https://github.com/Trigger726/OnCall-Agent/actions/runs/37156379925)十六success、H2failure、容器skipped；七ZIP源/实际摘要与重放已核验，真实MySQL8.4.11新换班17零跳过、Linux换班HTTP两项、原MySQL99/原生18/双JVM六项及原十三八六浏览器机器结果限定通过，见[85第五节](docs/acceptance/V1.7-checkpoint-85.md)/[首次远端证据](docs/assets/v1.7-cp85/first-remote-proof.json)。H2错误在MockMvc日志打印器遍历异步SSE响应头，不称整轮全绿或用86本地结果覆盖首次失败。

84自身3abb34c9/[Run37154511110](https://github.com/Trigger726/OnCall-Agent/actions/runs/37154511110)十七success，六ZIP来源/实际摘要核验、真实MySQL8.4.11独占schema双JVM六项/Provider10及最终SQL/双池关闭/原门禁重放通过，见[84第四节](docs/acceptance/V1.7-checkpoint-84.md)/[远端证据](docs/assets/v1.7-cp84/remote-proof.json)。原生MySQL18/原99/H2原生63、十runner/十九完整JAR日志/十三八六浏览器机器结果已核验；不覆盖85新换班事务、跨机器或生产规模。

83自身70af0fb/[Run37153507039](https://github.com/Trigger726/OnCall-Agent/actions/runs/37153507039)十七success，六ZIP独立核验；原双JVM四项在真实MySQL8.4.11/专属schema执行、零跳过，最终SQL/双池关闭/独立PID端口核验与保存的83原门禁重放通过，见[83第四节](docs/acceptance/V1.7-checkpoint-83.md)/[远端证据](docs/assets/v1.7-cp83/remote-proof.json)。MySQL原生18/原99/H2原生63、十runner/十九完整JAR日志意外ERROR0及十三/八/六浏览器机器流程通过；两次原SIGKILL不称优雅关闭，本次未重新目视截图，旧四项不覆盖84新排队矩阵。

82自身a502698/[Run37150940483](https://github.com/Trigger726/OnCall-Agent/actions/runs/37150940483)十六success，原五慢消费者/两503 JSON/模型0、未读旧TCP时自然回池59805ms/新原生答案60065ms已验证。五ZIP独立核验/CLI重放：MySQL原生18/原99/五池、H2原生63及十三/八/六浏览器机器结果通过，见[82第六节](docs/acceptance/V1.7-checkpoint-82.md)/[远端证据](docs/assets/v1.7-cp82/remote-proof.json)。不覆盖80/81首次失败、不认定唯一根因或由单JVM推导双JVM MySQL；以下历史‘待验’对应当时。

81自身 d1600ad/[Run37148364622](https://github.com/Trigger726/OnCall-Agent/actions/runs/37148364622)已核验：十四success、认证failure、容器skipped。原四项慢消费者和同快照双writer通过，第五项拒绝探针实际调用模型、旧JSON读取超时，原失败保留；不称全绿或唯一根因已解决。MySQL原生18、原99/H2原生63及十三/八/六界面机器结果独立核验通过，见[81第六节](docs/acceptance/V1.7-checkpoint-81.md)/[五ZIP证据](docs/assets/v1.7-cp81/first-remote-proof.json)。仅测试/CI变化，原五项/预算/TCP不变，56门禁与Windows本地五项通过不能覆盖Linux失败；历史Demo、80与81首次失败和以下历史“待验”均保留。

79轮转刷新验收本地限定通过：[报告](docs/acceptance/V1.7-checkpoint-79.md)、[证据](docs/assets/v1.7-cp79/local-proof.json)。真实响应在鼠标按压期间插入台账可令按钮位移3268px、click事件0/确认表单0；只改测试等待实际后续roster响应，保留200ms延迟/按压、全部原断言。最终原十三页/助手八/原生六页、三完整停机日志意外ERROR=0通过；自身Linux待验，不断言78远端唯一根因或产品布局缺陷已修复，旧Demo/失败保留。

78自然输出容量回收本地与自身Linux限定通过，但整轮CI失败：[报告](docs/acceptance/V1.7-checkpoint-78.md)、[远端证据](docs/assets/v1.7-cp78/first-remote-proof.json)。119cee1/Run37142602799十三作业success、轮转浏览器failure、容器skipped；默认五项/三JVM/五客户端/十二HTTP通过，旧TCP不读、Provider不放行时Linuxwriter60.224秒池空闲，新原生答案60.424秒提交。四ZIP源SHA/摘要、十五完整JAR日志意外ERROR=0、原生62和MySQL原99门禁已核验；本轮助手UI未执行，失败保留。不是绝对TTL或生产容量保证。

77自身远端限定通过：8a0714f的[Run37140503864](https://github.com/Trigger726/OnCall-Agent/actions/runs/37140503864)十五作业success，四ZIP源SHA/实际摘要、Linux四真实慢写场景/九HTTP/十六完整JAR日志、原生62、MySQL99/五池原门禁重放、十三/八/六页面流程已独立核验，见[报告阶段十三](docs/acceptance/V1.7-checkpoint-77.md)、[远端证据](docs/assets/v1.7-cp77/remote-proof.json)。两次自身远端失败、原包负对照及所有旧Demo保留，此源不覆盖78第五项或原生AI MySQL HTTP。

77初次本地范围（自身远端闭环见上）：[报告](docs/acceptance/V1.7-checkpoint-77.md)、[耐久证据](docs/assets/v1.7-cp77/local-proof.json)。有界输出池/单槽交接隔离真实Tomcat写阻塞，取消/撤销/原预算超时先关闭实际模型HTTP并复用唯一worker，满输出503接纳前问题0/模型0。原生六页面/九图、十三/八UI、跨节点四项及幂等/取消/预算回归通过，双时区521发现/415执行/106条件跳过、原生62/前端107/生命周期20零失败。有限writer仍等TCP或容器超时，已缓冲字节不能撤回；初次实现/页面夹具失败保留，不外推整体完成或生产容量。

76双节点助手四场景本地两次与自身远端限定通过：[报告](docs/acceptance/V1.7-checkpoint-76.md)。6082012的[Run37131134785](https://github.com/Trigger726/OnCall-Agent/actions/runs/37131134785)十五作业success，三ZIP源SHA/实际摘要、Linux四跨节点场景/六runner/十一完整JAR日志、原生54和真实MySQL99/五池门禁已独立核验。两个独立JVM共享新建SQL文件库，A执行、B回放/取消/清空/撤销；正常读取时原生流先关闭实际HTTP并复用worker，旧74两项均未释放。重复409/他人404/单次审计与不落半答案成立，不外推77慢读边界、跨机器、MySQL原生HTTP或数据库HA，历史Demo保留。

75默认助手原生流本地与自身远端限定通过：[报告](docs/acceptance/V1.7-checkpoint-75.md)。55d80e5的[Run37128573740](https://github.com/Trigger726/OnCall-Agent/actions/runs/37128573740)十五作业success，四ZIP源SHA/官方摘要/实际下载及原门禁重放已核验；54原生契约、双时区各513发现/407执行/106条件跳过、真实MySQL99/五池关闭、Linux原十三页面/八助手UI/五原生UI和五认证runner已验。真实模型结束前可见临时片段，SQL提交后才发布答案ID；取消/超时关闭原HTTP、worker可复用，截断不保存半个答案。前端107/生命周期17、新旧27图与失败保留。原生MySQL HTTP矩阵、跨节点、慢消费者/生产容量和整体目标继续。

历史74底层适配器的[Run37117383000](https://github.com/Trigger726/OnCall-Agent/actions/runs/37117383000)十五作业success，新增原生工件17项的源SHA/官方摘要/实际ZIP/XML/故障注入日志已独立核验，其他工件尚未独立重放，见[74报告](docs/acceptance/V1.7-checkpoint-74.md)。当时默认端点尚未接入，75另行补齐。以下历史检查点的“当前/尚未”等边界均对应当时，不替代75的新证据。

73助手界面本地与自身远端限定通过：[报告](docs/acceptance/V1.7-checkpoint-73.md)。79e4d5e的[Run37114973597](https://github.com/Trigger726/OnCall-Agent/actions/runs/37114973597)十五作业success，三ZIP源SHA/摘要、原MySQL门禁99/五池关闭、Linux八界面流程/原十三页面/五认证runner与完整日志已独立核验。冻结原键/问题、显式取消、手动恢复、真实503和晚200身份围栏成立；前端103/生命周期17/audit0及新旧Demo保留。默认对话仍完整回答后分块，原生token流/生产规模与整体目标继续。

72后端限定验收已闭环：390dd7c的[Run37111174821](https://github.com/Trigger726/OnCall-Agent/actions/runs/37111174821)十五作业success，真实MySQL99/V33/五池关闭、Linux取消/丢响应/排队SIGKILL及两ZIP源SHA/摘要/原门禁重放已独立核验，见[72报告](docs/acceptance/V1.7-checkpoint-72.md)。该提交没有默认页面稳定键/取消按钮；73另补界面，旧JAR/Demo/失败保留，整体目标继续。

71自己的bd508bd已核对[Run37108835199](https://github.com/Trigger726/OnCall-Agent/actions/runs/37108835199)十五作业success，真实MySQL96/V32/五池关闭、Linux幂等三个JVM强制终止与两ZIP源SHA/实际摘要/原门禁重放已独立核验，见[71报告](docs/acceptance/V1.7-checkpoint-71.md)。不借旧96证明72的V33/取消事务99项。

70自己的5cae132已核对[Run37096366762](https://github.com/Trigger726/OnCall-Agent/actions/runs/37096366762)十五作业success，真实MySQL93零失败错误跳过/五池关闭、认证/助手两ZIP源SHA与实际摘要及Linux十一模型HTTP已独立核验，见[70报告](docs/acceptance/V1.7-checkpoint-70.md)；不借旧93证明71新增V32/三事务。

69自己的45247a8已核对[Run37095079404](https://github.com/Trigger726/OnCall-Agent/actions/runs/37095079404)：十三success/MySQL failure/最终容器skipped，不能标全绿。认证ZIP源SHA/摘要及Linux三结果/四停机日志通过；真实MySQL93中CHECK异常类别fixture失败，另四个助手事务执行通过；70修正为精确SQL错误/约束名并继续检查回滚，不放宽门禁。见[69报告](docs/acceptance/V1.7-checkpoint-69.md)。

68自己的40e0c2f已补[Run37092997128](https://github.com/Trigger726/OnCall-Agent/actions/runs/37092997128)十五作业success；认证/助手实际生产HTTP七次/四份JAR日志、MySQL88一般兼容回归/五池关闭与两ZIP源SHA摘要及精确原门禁重放已核验。后来发现的更晚提交/同步返回空窗由69另列，旧Demo/首连接Future三秒超时仍保留，不借旧绿灯外推新五事务矩阵。见[68报告](docs/acceptance/V1.7-checkpoint-68.md)。

67已补客户端自身远端验收：3b6247b的[Run37090872485](https://github.com/Trigger726/OnCall-Agent/actions/runs/37090872485)十五作业completed/success，浏览器ZIP源提交/官方digest与实际SHA256相符，Linux十三脚本退出码0、三入口真实logout/旧401/新admin凭证保留与服务端200已独立核对；九PNG实际存在，本次工件重放未重新目视截图。c903d59后端真实MySQL88/五池关闭及三工件定向重放原结论保留，跨节点、Trace偶发控制失败与完整目标仍继续。见[67报告](docs/acceptance/V1.7-checkpoint-67.md)。

检查点66限定通过：7705b8d的[Run37058813999](https://github.com/Trigger726/OnCall-Agent/actions/runs/37058813999)十五作业success，真实MySQL86零跳过/五池关闭、四ZIP源SHA/摘要与重放已核验；新增本人账号安全页、改密/确认退出全部会话与明确重登；修复错误当前密码误退出和旧401踢新身份，刷新入口/数据鉴权正反对照通过。前端72/全树audit0、双时区375发现282执行93条件跳过、十二实页脚本/同库跨JVM通过，真实提交后丢响应不重试、新凭证能恢复。新旧桌面/390px和失败证据保留，见[66报告](docs/acceptance/V1.7-checkpoint-66.md)。既有SSE/任务再授权及完整目标继续。

检查点65服务端限定通过：0f21f68的[Run37055729127](https://github.com/Trigger726/OnCall-Agent/actions/runs/37055729127)十五作业success；本人改密/退出全部会话、持久化版本校验、事务审计、BCrypt字节边界。双时区374/281/93、真实MySQL86零跳过/五池关闭、Linux同库跨JVM与三ZIP源SHA/digest/重放已验，见[65报告](docs/acceptance/V1.7-checkpoint-65.md)和[升级/API边界](docs/AUTH-SESSIONS.md)。保留全部旧Demo与失败；该提交没有自助UI，66另行补齐；既有SSE/任务再授权及完整目标继续。

检查点64限定通过：真实复现构建链brace-expansion2.1.4的栈耗尽，以及vue-tsc“打印错误但exit0”的隐蔽失败；仅锁文件三字段升到2.1.7，加4项隔离/有时限真实引擎与编译器回归、CI全树官方audit（含dev）。`0e76296`的[Run37049527001](https://github.com/Trigger726/OnCall-Agent/actions/runs/37049527001)十四作业success，前端62/审计0/双时区338发现255执行83条件跳过/真实MySQL76及十一脚本、五ZIP摘要与重放已验。新旧Demo都保留、四静态文件逐字节一致，非线上HTTP漏洞利用或UI改版，见[64报告](docs/acceptance/V1.7-checkpoint-64.md)。账号永久撤销/任务再授权、生产容量与其余完整路线继续。

检查点63限定通过：新增显式可选Collector→原生Prometheus→文件凭证Alertmanager→CMDB/Incident链路。真实未知库存拒绝及修复后重复送达、两条告警重复无写/原ID恢复/各一次时间线已验，Incident仍OPEN不冒充人工回执。`7e81395` 的[Run37045120031](https://github.com/Trigger726/OnCall-Agent/actions/runs/37045120031)十四作业success，四ZIP摘要和原始数据重放通过，见[63报告](docs/acceptance/V1.7-checkpoint-63.md)与[可选启用说明](docs/COLLECTOR-ALERTING.md)。两次失败、62监控-only及历史Demo/JAR均保留；生产容量/磁盘卷故障、依赖安全修复和完整目标继续。

检查点62限定通过：默认十消费者SIGKILL/新容器/同卷恢复原调查完整Trace与10/10在途探针；真实队满中12个OTLP200仍最终缺失，原生队列/拒绝告警触发、恢复后独立已知ID送达。`4cdda31` 的 [Run37039755121](https://github.com/Trigger726/OnCall-Agent/actions/runs/37039755121) 十三作业success，三工件摘要及六图/逐ID/MySQL重放已核对；旧Demo与三轮失败保留。Collector反馈仅到Prometheus，未验Alertmanager投递/生产容量/磁盘卷故障，见[62报告](docs/acceptance/V1.7-checkpoint-62.md)与[复跑说明](docs/COLLECTOR-QUEUE-RECOVERY.md)，整体目标继续。

检查点61限定通过：Collector发送队列加独立卷与file_storage，同步落盘/文件上限/非root初始化；旧内存版SIGKILL重建队列7→0、原Trace404，新版6→6、同一原Trace及两跨JVM分支完整恢复。5ed5a9f的[Run36999165718](https://github.com/Trigger726/OnCall-Agent/actions/runs/36999165718)十三作业success，三工件摘要及图结构/MySQL重放已验，历史Demo保留；生产容量、磁盘/队满与卷丢失不在保证范围，见[验收报告](docs/acceptance/V1.7-checkpoint-61.md)与[操作边界](docs/COLLECTOR-QUEUE-RECOVERY.md)。整体目标继续。

检查点60限定通过：签发uid绑定当前账户ID，阻止同名重建继承旧Token；继续自检修复极值exp/iat/nbf即使错误签名仍500，现为结构化401。b1a0945的[Run36996179284](https://github.com/Trigger726/OnCall-Agent/actions/runs/36996179284)十三作业success，真实MySQL76零跳过/八场景/五池干净关闭、双时区338发现/255执行/83条件跳过、58前端与Linux十一脚本及最终容器通过；工件摘要/CLI重放/实页已验。实际新旧JAR12请求500→401、异常12→0；两份旧JAR、首失败和历史Demo保留，见[验收报告](docs/acceptance/V1.7-checkpoint-60.md)。永久撤销/人工ID复用未完成，整体目标继续。

检查点59限定通过：真实HTTP复现账户停用后旧JWT仍读身份/写备注200，入口现补资格检查，停用/删除统一401、活跃降权403。aa76689的[Run36992121756](https://github.com/Trigger726/OnCall-Agent/actions/runs/36992121756)十三作业success，真实MySQL73零跳过/五池干净关闭、双时区291发现/211执行/80条件跳过、58前端与Linux十脚本通过；五个命名HTTP场景及实际审计IP、工件摘要/CLI重放已核验。首轮MySQL请求fixture失败、旧JAR与Demo保留；永久Token撤销及既有任务再授权仍未完成，见[验收报告](docs/acceptance/V1.7-checkpoint-59.md)。

检查点58限定通过：SLO 阈值随目标周期计算，修复正分数事件舍入导致的误报健康；管理页面导出捕获版本的 Prometheus 规则并核验摘要，旧版本409阻断下载。bbe00f9的[Run36989009983](https://github.com/Trigger726/OnCall-Agent/actions/runs/36989009983)十三作业success，原生20场景/真实重复firing与同一告警resolved、双时区281发现/206执行/75条件跳过、真实MySQL68零跳过/五池关闭、58前端与Linux十脚本已验，三个工件摘要/重放/实图已核对。新旧Demo保留；规则发布仍由运维执行，生产长期数据与低流量策略待验证，见[检查点58](docs/acceptance/V1.7-checkpoint-58.md)及[规则发布说明](docs/SLO-PROMETHEUS-RULES.md)。

检查点57已补远端验收：缺失/null审核版本六例拒绝，显式0合法。e98ee3a 的 [Run36344164602](https://github.com/Trigger726/OnCall-Agent/actions/runs/36344164602) 十二作业success，双时区270/195/75、真实MySQL68/五池关闭、58前端和Linux九脚本（含六例真实HTTP拒绝/补0成功）已核验，见[阶段报告](docs/acceptance/V1.7-checkpoint-57.md)。

检查点56基线已验：Runbook导入待审、另一当前管理账号独立发布、拒绝/本人撤回、基线保护及精确重放。8b5c95b的[Run36343295826](https://github.com/Trigger726/OnCall-Agent/actions/runs/36343295826)十二作业全绿，前端58项、双时区各264/189/75、真实MySQL68零跳过/五池关闭与Linux扩展九脚本工件已验；通知真实接收器503→204/同键/重复扫描零新增、最终容器烟测通过。eb47adb首失败/403仍保留；该基线未覆盖的缺失版本问题由57继续补，不宣称整体完成。见[报告](docs/acceptance/V1.7-checkpoint-56.md)，新旧Demo继续保留。

checkpoint55限定通过：新增持久化查询逐日趋势，区分结果返回率、非空查询全量独立复核覆盖、可计分子集Hit@K与历史未知；V29恢复旧有效快照计数、保留清理后分母。`49c0161` 的 [Run36338448177](https://github.com/Trigger726/OnCall-Agent/actions/runs/36338448177) 十二作业success，真实MySQL63项零跳过/五池干净关闭、50项前端、双时区各250发现/180执行/70条件跳过、八脚本与10项生命周期通过；新旧/远端桌面手机、工件摘要与完整MySQL CLI重放已验，见 [验收报告](docs/acceptance/V1.7-checkpoint-55.md)。不冒充全量生产相关性或版本效果提升。

checkpoint54限定通过：覆盖详情、独立管理撤销确认、账号隔离冻结意图恢复/409锁定及日历刷新已交付，原接受事实与历史Demo保留。`ad701b7` 的 [Run36335732140](https://github.com/Trigger726/OnCall-Agent/actions/runs/36335732140) 十二CI作业success，前端47项、真实MySQL62项零跳过/五池干净关闭、双时区各244发现/175执行/69条件跳过与七脚本通过；新旧桌面/手机、工件摘要与完整MySQL CLI重放已验，见 [验收报告](docs/acceptance/V1.7-checkpoint-54.md)。双向互换/开放认领、外部渠道和跨时区/DST仍未实现。

checkpoint 52 工程修复已验：五套真实MySQL容器由Spring管理并在类结束时显式关闭上下文，补完整日志/五池停机/零跳过门禁。代码 `276b57b` 的 [Run36329512979](https://github.com/Trigger726/OnCall-Agent/actions/runs/36329512979) 十二作业success，真实MySQL52项执行、五池完整关闭、日志拒绝项0、无强制fork退出；12项门禁测试、双时区、39项前端与浏览器五脚本通过，ZIP摘要及失败/成功工件重放已验。51旧错误和52首次严格门禁失败保留，页面/历史Demo不改，见 [工程验收报告](docs/acceptance/V1.7-checkpoint-52.md)。

checkpoint 51 定向接班页面已验：从本人普通班次申请，指定接班人接受/拒绝、申请人撤回；接受后自动刷新班次/覆盖日历。响应丢失后按账号恢复冻结草稿并同键重试，409锁定旧决定版本而不自动重提。代码 `49c221c` 的 [Run 36328347791](https://github.com/Trigger726/OnCall-Agent/actions/runs/36328347791) 十二作业成功，前端39项、双时区回归、真实MySQL52项断言、Linux隔离JAR五脚本通过；新旧桌面/390px与远端工件已核验。当时发现的MySQL后台任务泄漏/fork强制退出已由检查点52修复，51报告保留原始诊断，不宣称完整换班产品完成，见 [限定验收报告](docs/acceptance/V1.7-checkpoint-51.md)。

checkpoint 50 补接班台账筛选：`scope=MINE` 从登录身份匹配申请/接班双方，可与计划、状态组合，SQL先过滤再截断，避免较旧待办被201条无关新请求遮住。代码 `a87265a` 的 [Run 36326409469](https://github.com/Trigger726/OnCall-Agent/actions/runs/36326409469) 十二项全绿，双时区各224发现/165执行/59条件跳过、真实MySQL52执行/零跳过、接班17项通过；旧页面/JAR/HTTP回归已验，当时未交付的接班页面已由检查点51补齐，详见 [阶段报告](docs/acceptance/V1.7-checkpoint-50.md)。

checkpoint 49 定向接班后端已验：本人申请、指定接班人接受/拒绝、申请人撤回；同事务新增临时覆盖保留原班次，用幂等键、版本和最新资格防重复或越权。修复提交 `eb3ef78` 的 [Run 36325182213](https://github.com/Trigger726/OnCall-Agent/actions/runs/36325182213) 十二项全绿，真实MySQL50项零跳过、双时区各220项发现/163项执行、真实JAR HTTP与原页面回归通过；当时未交付的接班UI/新旧对照已由51补齐，仍不宣称完整换班产品。首轮SSE响应头竞态与修复证据保留，详见 [阶段报告](docs/acceptance/V1.7-checkpoint-49.md)。

checkpoint 48 新增日历覆盖预览：已持久化班次按实际路由优先级分段，逐日查看有效覆盖/缺班、被遮盖与无资格班次，取消后自动重算；不是未生成轮转的预测，也不是历史账号资格快照。代码 `4573a82` 的 [Run 36291809846](https://github.com/Trigger726/OnCall-Agent/actions/runs/36291809846) 十二项全绿，前端29项、真实MySQL35项零跳过、双时区各189项发现/147项执行/42项条件跳过、Linux桌面/390px与完整停机日志通过，详见 [验收报告](docs/acceptance/V1.7-checkpoint-48.md)。

- 告警治理：外部事件 ID 幂等、SHA-256 指纹压缩、30 分钟窗口聚合、原始告警与 Incident 分层；原生接收 Alertmanager v4 批量 webhook，同状态重试零写入、firing/resolved 共用生命周期，批内永久坏项进入脱敏台账并支持角色受控重放。
- Incident 工作台：`OPEN -> ACKNOWLEDGED -> INVESTIGATING -> MITIGATED -> RESOLVED -> CLOSED` 状态机、乐观锁、分派、备注和时间线。
- CMDB：应用、API、数据库和中间件台账，依赖/调用关系拓扑，事故与近期变更关联。
- 值班升级：管理角色可创建普通/临时覆盖班次，计划行锁防同层重叠，带版本/原因软取消并保留历史与审计；当前值班和新事故路由排除取消班次。有序成员轮转 API/页面默认每分钟续排未来 14 天开始的班次，冲突/不可用成员留台账，已取消生成班不复活，暂停/恢复受版本与原因约束。P1 未确认 Incident 按 0/10/20 分钟分级路由，首步在告警创建事务内执行，后续分钟扫描，确认后停止。缺班记录 `NO_TARGET`；`ROUTED` 仅是站内事实，非外部送达；跨时区/DST、换班和日历同步仍待建设。
- 可解释 Agent 调查：以 `PLAN -> EXECUTE -> REPLAN -> FINISH` 编排告警、CMDB、指标、变更、日志和 Runbook 六个只读工具；每步持久化输入、查询范围、数据源、证据、失败原因和耗时。
- 可恢复调查事件流：运行事件先落库再通过 SSE 实时发送，事件 ID 同时作为断线回放游标；普通断网/本地退出不取消后台调查。全部会话撤销、到期或资格丧失由持续授权检查关闭原流并安全取消任务，不承诺撤回已发送字节或立即停止外部请求。
- Agent 运行控制：同一 Incident 使用幂等键抑制重复 run；任务先进入有界队列，可显式取消并受持久化截止时间预算约束；取消、超时和队列拒绝都形成可回放的持久化终态，执行 JVM 崩溃后由存活实例幂等结算逾期孤儿 run。
- Runbook 知识库：Markdown/PDF 入库、内容哈希幂等、不可变版本、角色 ACL 和标题分块；本地 BM25 与可选 DashScope 向量召回通过 RRF 融合，返回 `runbook:{stableKey}:v{version}#chunk-{index}` 稳定引用。向量未启用、覆盖不足或 Provider 失败时显式降级 BM25；真实检索快照在入库前脱敏并按可配置保留期自动擦除，提交人与复核人分别给出 0–3 级评分，复核前隐藏原始等级，并以线性加权 Cohen's kappa 量化一致性；批准后的分级 qrels 在原快照清理后仍可按唯一查询计算 Recall@3、MRR、NDCG@3 和引用命中率。
- 可观测数据适配：统一 Metrics/Logs Provider SPI；默认使用可复现的本地证据库，可选调用 Prometheus 与 Loki HTTP API，外部失败后自动重试、熔断并降级到本地证据。
- 端到端调查 Trace：基于 Micrometer Tracing + OpenTelemetry，从告警接入串联异步 Agent run、工具步骤和指标/日志 Provider；Prometheus/Loki 出站请求使用 Spring Boot 管理的 `RestClient.Builder` 自动创建 HTTP client span 并注入 W3C `traceparent`；仅写入业务 ID、状态和类型标签，不将告警正文、查询表达式或凭证放入 span。
- 证据报告：规则引擎离线生成假设、置信度和建议，可选 DashScope 生成受约束摘要；单工具失败时保留其他证据并降级为部分完成。
- OnCall 助手：持久化多轮会话、SSE 流式输出、Incident 上下文绑定、证据引用、会话清空/删除和 Markdown 导出。
- 受控处置：高置信度变更关联可生成回滚草案；管理员或运维经理独立审批，禁止申请人自批，并用乐观锁防止并发覆盖。审批只解除治理门禁，不自动修改生产环境。
- 无责事故复盘：只有已恢复/已关闭 Incident 才能从当时的时间线、告警、调查报告和变更引用生成脱敏快照；五类复盘内容必须补全，并绑定有负责人和期限的防复发行动项后才能提交。提交人不能自审，发布后正文冻结，行动项仍可由负责人闭环，全部过程进入时间线和审计。
- 行动项发布门禁：未发布复盘的草稿行动项不会被后台逾期扫描升级或外部通知，也不能提前标记完成；历史草稿通知在派发与人工重试时再次检查发布状态，避免未经复核的内容外发。
- 事故运营分析：按创建窗口与严重等级计算 MTTA/MTTM/MTTR 的均值、中位数和独立样本数，排除缺失与负时长；按 CMDB 归属服务拆分事故量、未关闭数和有效里程碑分母，并提供慢事故下钻。跨 Incident 管理行动项、逾期天数和持久化升级事实，重复扫描幂等、完成后关闭且不冒充外部通知送达；这些响应指标不冒充可用性 SLO。
- 服务 SLO：按 CMDB 服务持久化目标、滚动窗口和版本化 PromQL 模板，从 Prometheus 分别读取好事件与总事件，计算 SLI、错误预算和三档长短窗口燃烧率，阈值随目标周期计算。1/2天目标相应缩短票据窗口。关闭、失败、零分母、多序列或矛盾数据均显式拒算。管理角色可带乐观锁调整目标，并导出捕获版本的记录/告警规则，经原生校验后由运维发布到 Prometheus；Alertmanager 送回 OpsPilot，重复投递幂等，恢复告警保留 Incident 人工处置流程。
- 重复事故与 Problem 治理：按“归属服务 + 精确告警指纹”识别跨 Incident 复发，明确区分独立事故数和单次事故内的告警 occurrence；管理角色可将候选提升为唯一 Problem，维护 `OPEN / KNOWN_ERROR / RESOLVED`、根因、规避方案和长期解决说明。新同指纹 Incident 自动幂等关联，已解决后复发只显示事实、不静默重开。
- 安全审计：JWT、BCrypt、角色权限、关键操作审计、Prometheus 指标和健康检查。
- 运维控制台：Vue 3 + TypeScript，高密度桌面工作台及移动端响应式视图。

## 技术栈

| 层次 | 技术 |
| --- | --- |
| 后端 | Java 17, Spring Boot 3.2, Spring Security, Spring Retry, JdbcClient, Flyway, Apache PDFBox |
| AI | Spring AI Alibaba / DashScope，可选启用 |
| 数据 | H2 本地零配置，MySQL 8.4 生产化部署 |
| 前端 | Vue 3, TypeScript, Vite, Vue Router, Lucide |
| 可观测性 | Spring Boot Actuator, Micrometer, OpenTelemetry/OTLP, Prometheus, Loki |
| 工程化 | Maven Wrapper, Docker multi-stage build, Docker Compose, JUnit 5, MockMvc, Testcontainers, GitHub Actions |

## 架构

```text
Prometheus / APM / manual event
              |
              v
       Alert Intake API
              |
    dedupe + fingerprint + group
              |
              v
         Incident domain <------ CMDB topology
          |     |     |               |
          |     |     +---------- change records
          |     +---------------- on-call escalation
          +---------------------- timeline / audit
              |
              v
 Agent run: PLAN -> 6 read-only tools -> REPLAN -> FINISH
              |                         |
              v                         v
      evidence report        persisted step + event log
              |                  |               |
              |                  |               +--> SSE + cursor replay
              |                  v
              +--------> remediation proposal --> independent approval
              |
              +----> OnCall conversation (history + SSE + evidence refs)
              |
              +----> resolved incident -> evidence snapshot -> blameless postmortem
                                                      |            |
                                                      +-> follow-up +-> independent publish
              |
              +----> exact cross-incident fingerprint -> recurrence candidate
                                                          |
                                                          +-> Problem lifecycle / known error

 Observability providers: Prometheus -> local metrics / Loki -> local logs
 Agent controls: idempotency -> bounded queue -> deadline / cancel -> terminal event
 Runbook retrieval: immutable ACL chunks -> BM25 + optional vectors -> RRF / fallback -> citation
                         |                                      |
                         +-> redact -> retained snapshot -> blind review -> graded qrels
                                             |                    |
                                             +-> timed purge ---->+ (qrels survive)
```

详细设计见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 快速启动

前置条件：JDK 17+、Node.js 20+。

```bash
cd web
npm ci
npm run build
cd ..
./mvnw spring-boot:run
```

Windows PowerShell 使用：

```powershell
cd web
npm ci
npm run build
cd ..
.\mvnw.cmd spring-boot:run
```

打开 [http://localhost:9900](http://localhost:9900)。默认使用本地 H2 文件数据库，首次启动由 Flyway 自动建表和写入演示数据，不需要安装 MySQL 或配置 AI Key。

### 演示账号

所有账号的初始密码均为 `OpsPilot@2026`。

| 账号 | 角色 | 典型权限 |
| --- | --- | --- |
| `admin` | ADMIN | 管理功能；仍受本人确认、禁止自审等业务约束 |
| `zhangwei` | ON_CALL | 事故处置与调查 |
| `lina` | OPS_MANAGER | 运行管理与事故处置 |
| `auditor` | AUDITOR | 审计只读，不能流转 Incident |

## Docker 部署

```bash
docker compose up --build -d
```

- OpsPilot: [http://localhost:9900](http://localhost:9900)
- Prometheus: [http://localhost:9090](http://localhost:9090)
- 管理端口：`http://127.0.0.1:9920/actuator/health` 与 `/actuator/prometheus`；业务端口 `9900` 不再提供指标。
- MySQL 仅在 Compose 内网开放。

直接运行 JAR 时，管理端口默认绑定 `127.0.0.1:9920`，可用 `MANAGEMENT_PORT` 和 `MANAGEMENT_ADDRESS` 调整。Compose 为让同网络 Prometheus 抓取，将容器内管理监听地址设为 `0.0.0.0`，但只把宿主机的 `127.0.0.1:9920` 映射出去；Prometheus 的宿主机 `9090` 也只绑定回环地址。远程部署仍须以防火墙或私有网络限制容器网络和管理端口，不能把此演示配置当作生产认证方案。

需要演示真实 Trace 存储和查询时启用可选 `tracing` profile：

```bash
OTEL_TRACING_ENABLED=true \
OTEL_TRACING_SAMPLING_PROBABILITY=1.0 \
docker compose --profile tracing up --build -d
```

- Grafana Trace 查询：[http://localhost:3000](http://localhost:3000)
- Tempo API：[http://localhost:3200](http://localhost:3200)
- OpenTelemetry Collector 健康检查：[http://localhost:13133](http://localhost:13133)

该 profile 使用固定版本的 OpenTelemetry Collector、Tempo 与 Grafana，Grafana 已预置 Tempo 数据源；`1.0` 采样率只用于受控演示，普通 `docker compose up` 仍保持 Trace 关闭和零额外依赖。

生产部署前必须替换 `JWT_SECRET`、数据库口令和演示账号密码；不要将 `.env` 或真实 `DASHSCOPE_API_KEY` 提交到仓库。

## AI 模式

核心流程默认不依赖外部模型：

```bash
AI_ENABLED=false
```

启用 DashScope：

```bash
AI_ENABLED=true
DASHSCOPE_API_KEY=your-key
```

启用后，模型只能基于系统已收集的结构化证据生成研判摘要和对话回答。证据、规则假设、置信度和建议仍单独持久化，模型失败会自动回退到规则结果；OnCall 对话在没有 Key 时仍可回答告警、变更、状态和下一步动作。

Runbook 语义索引与生成式 AI 独立开关。默认仍使用零外部依赖的 BM25；需要验证真实向量召回时配置：

```bash
RUNBOOK_SEMANTIC_ENABLED=true
RUNBOOK_EMBEDDING_MODEL=text-embedding-v4
DASHSCOPE_API_KEY=your-key
```

管理员或运维经理再从“处置手册”执行索引重建。系统先在事务外批量生成向量，校验数量、维度和有限值，再在单个事务中原子替换当前 `provider + model` 索引；内容指纹未变化时幂等复用。重建失败保留上一版索引，但查询仅在已发布可见分块覆盖率达到门槛时进入 Hybrid，否则返回明确降级原因。

## 外部可观测数据接入

本地演示默认关闭外部依赖。需要查询 Prometheus 时配置：

```bash
PROMETHEUS_ENABLED=true
PROMETHEUS_BASE_URL=http://localhost:9090
PROMETHEUS_QUERY_TEMPLATE='up{job="%s"}'
```

查询表达式、故障时间窗、Provider、外部引用和降级信息会进入 Agent 步骤轨迹。Prometheus 超时或连续失败时由重试、熔断和本地指标 Provider 保持调查可用。

接入 Loki：

```bash
LOKI_ENABLED=true
LOKI_BASE_URL=http://localhost:3100
LOKI_QUERY_TEMPLATE='{resource_code="%s"}'
LOKI_TENANT_ID=energy-prod
LOKI_BEARER_TOKEN=your-token
```

Loki Provider 使用 `query_range` 查询故障时间窗，支持租户头和 Bearer Token，解析 stream labels 与常见 JSON 日志字段，并在形成证据前脱敏。Loki 超时、协议异常或熔断时会切换本地日志 Provider；实际 Provider 和 warning 均写入步骤轨迹。外部凭证只通过环境变量注入，不进入状态接口或调查报告。

## OpenTelemetry Trace

本地演示默认关闭 Trace 导出，不因 Collector 缺失产生网络噪声。部署环境可启用 OTLP/HTTP：

```bash
OTEL_TRACING_ENABLED=true
OTEL_TRACING_SAMPLING_PROBABILITY=0.1
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://otel-collector:4318/v1/traces
```

业务 span 固定为 `opspilot.alert.intake`、`opspilot.agent.run`、`opspilot.agent.tool`、`opspilot.provider.query`。Spring Boot 自动创建的入站 HTTP span 是根链路；调查进入有界线程池前捕获当前 span，因此客户端请求结束后才开始的工作线程仍保留同一 traceId。Prometheus/Loki Provider 注入 Boot 预配置的 `RestClient.Builder`，出站请求会在 `opspilot.provider.query` 下生成 HTTP client span，并以 W3C `traceparent` 把同一 traceId 传播给下游。生产采样率需按流量与成本调整，`1.0` 只适合受控验收。

仓库的独立 Trace 门禁会启动 OpsPilot、Collector、Tempo、Grafana 和一个仅用于验收的独立 Provider fixture。OpsPilot 对该服务发出 Prometheus/Loki 兼容查询，提交真实告警并执行六工具调查；TraceQL 按 run ID 找回链路，Grafana 数据源代理读取同一 trace。门禁除断言 `1 run -> 6 tool -> 2 provider` 外，还要求两个 OpsPilot HTTP client span 与 fixture 的两个 server span 在 Tempo 中共享 traceId，且 server parentSpanId 与 client spanId 逐一相等，并确认哨兵告警正文未进入导出数据。同一门禁还会停止 Tempo 后再执行一次调查：业务必须仍完成且应用健康，Collector 必须记录真实导出失败，Tempo 在 60 秒重试窗口内恢复后必须读回同一 run 的完整跨服务 trace：

```bash
bash scripts/verify-tracing-pipeline.sh
```

脚本使用隔离的 Compose project/volume 并在退出时清理，不会删除日常 `docker compose up` 使用的 MySQL 数据卷；fixture 只在 `tracing-test` profile 启动，普通 `tracing` 演示不加载它。checkpoint-24 的独立集成测试仍保留，用两个本机 HTTP 端点验证 W3C `traceparent` 与导出的 client span 一一对应。checkpoint-25 的远端 Run 58 已验证两个独立 JVM 在正常与 Tempo 短暂停机恢复后都形成 `provider.query -> CLIENT -> SERVER`，各有两条配对分支；fixture 不是生产 Prometheus/Loki 集群，内存队列也不支持 Collector 重启后的零丢失承诺。

## Alertmanager webhook

接入端点默认关闭。生产或联调环境必须显式设置共享密钥，并在 Alertmanager webhook receiver 中发送 `Authorization: OpsPilot <secret>`：

```bash
ALERTMANAGER_WEBHOOK_SECRET=replace-with-a-long-random-secret
ALERTMANAGER_WEBHOOK_MAX_ALERTS=100
ALERTMANAGER_AUTO_REPLAY_ENABLED=false
ALERTMANAGER_REJECTION_PAYLOAD_RETENTION=P3D
```

每条 alert 需要 `labels.alertname`、`labels.resource_code`（或 `service_code`）、可映射的 `labels.severity`、`status`、`startsAt` 和 `fingerprint`；resolved 还需要 `endsAt`。同一 fingerprint 和 startsAt 的重试返回原结果，不增加次数；状态变化更新同一条告警。HTTP 200 可能同时包含接受与拒绝项，拒绝项会脱敏后持久化；先修复上游规则或 CMDB，再由 ADMIN/OPS_MANAGER/ON_CALL 在告警页受控重放，AUDITOR 仅可查看。`RESOURCE_NOT_FOUND` 可显式开启自动重放，默认关闭；自动尝试有租约、批次/次数上限及退避抖动，耗尽后仍可手动重放。拒绝快照默认三天后按批清理，已清理项需重新投递原始告警；生产应按本地保留政策调整。这是 Alertmanager 入站 receiver，不是 SLO 出站通知配置。

真实 receiver 联调可在具有 Docker、`bash`、`jq` 和 `openssl` 的环境运行 `bash scripts/verify-alertmanager-pipeline.sh`。脚本使用独立 Compose project/volume 和运行时生成的测试密钥，向 Alertmanager API v2 注入 firing、resolved 和混合好坏项，再核对 OpsPilot 的 Alert、恢复时间线、拒绝台账及脱敏结果；不会修改日常 Compose 数据卷。这验证 Alertmanager → OpsPilot，不代表生产 Prometheus 规则链路。

规则到 Incident 的联调运行 `bash scripts/verify-prometheus-alerting-pipeline.sh`：隔离的 Pushgateway 测试指标由 Prometheus 抓取，规则先 firing 再恢复，经 Alertmanager 更新 OpsPilot 同一 Alert。脚本保留 Prometheus 状态、MySQL 摘要和容器日志；Pushgateway 仅是此测试夹具，不是通用生产采集方案。

真实服务指标联调运行 `bash scripts/verify-service-metrics-alerting-pipeline.sh`：Prometheus 从内部 `opspilot:9920/actuator/prometheus` 抓取 OpsPilot 自身指标，短窗口 HTTP 401 计数规则先触发后自然恢复，再验证 Alertmanager 更新同一 Alert。此隔离规则用于可复现验收，不代表生产阈值已调优。

## 未确认 Incident 的值班升级

新 P1 Incident 创建时立即执行策略的零分钟步骤；之后默认每分钟扫描仍为 `OPEN` 的事故，在 10/20 分钟到期时路由活跃值班人、指定用户或角色。每个 Incident/步骤最多一条持久化执行记录，并进入时间线、审计及站内通知日志。重叠班次优先临时覆盖；没有生效班次或活跃账号时记 `NO_TARGET`，不写虚假的通知。管理角色可手动触发扫描，值班页保留原有班次与策略展示，并增加最近 50 步执行记录。`ACKNOWLEDGED` 后不再升级；迟到扫描会补处理已到期且未执行的步骤。默认定时执行，可设置 `OPSPILOT_ONCALL_ESCALATION_ENABLED=false` 关闭。

当前种子班次属于 2026-08 的历史示例，已过期；没有新增有效排班时，演示页面会如实显示“无生效班次”。真实部署需维护有效排班；普通/覆盖班次编辑与取消、轮转管理、覆盖日历已分别在 checkpoint 43/46/48 补齐。升级当前仍只记录站内路由，没有短信/电话投递或人工已读回执；早期升级证据见 [checkpoint-42](docs/acceptance/V1.7-checkpoint-42.md)，有效覆盖见 [checkpoint-48](docs/acceptance/V1.7-checkpoint-48.md)。

## 逾期行动项外部提醒

默认仅保留应用内逾期升级事实。设置 `FOLLOW_UP_NOTIFICATION_ENABLED=true`、`FOLLOW_UP_NOTIFICATION_URL=https://...` 和 `FOLLOW_UP_NOTIFICATION_TOKEN` 后，新产生的逾期升级会在同一数据库事务内入队，后台以 `Authorization: Bearer` 和稳定的 `Idempotency-Key: follow-up-escalation:{id}` POST JSON 到配置的接收端。生产 URL 必须为 HTTPS；本机联调允许 `localhost/127.0.0.1` HTTP。已存在的历史升级不会因后来开启通知而追溯投递。

接收端收到入队时冻结的 `eventType`、`escalationId`、`followUpId`、`incidentCode`、行动项标题、负责人和截止日。2xx 记录为“端点已收”；503/429/网络故障有限退避，3xx 和其余 4xx 直接标记失败；仅管理员/运维经理能重试仍开放的失败通知。接收端应按幂等键去重：响应丢失时可能再次发送。页面上的回执不代表负责人已读，token 不写入数据库或诊断日志。交付配置与失败明细见 [checkpoint-35 验收记录](docs/acceptance/V1.7-checkpoint-35.md)。

已发布复盘的开放行动项由当前负责人在运营页或 Incident 详情点击“确认接手”，系统单独保存确认人与时间，并写时间线/审计。此动作不依赖通知开关，不会把行动项标记完成或关闭逾期升级；完成仍需单独提交。重复确认幂等，其他用户（包括管理员）不能代替负责人确认。运营页另有开放未确认数及确认状态筛选，便于定位待接手任务。见 [checkpoint-37](docs/acceptance/V1.7-checkpoint-37.md) 与 [checkpoint-38 验收记录](docs/acceptance/V1.7-checkpoint-38.md)。

在有 Docker、`bash`、`jq`、`openssl` 的环境运行 `bash scripts/verify-follow-up-notification-pipeline.sh`，可用隔离 MySQL、OpsPilot 和单独 Node 接收容器验证 503→204 自动重试与重复扫描去重。接收容器共享 OpsPilot 网络命名空间以使用本机 HTTP 限定；这是独立进程联调，不代表跨宿主机 HTTPS 或第三方消息平台实接。见 [checkpoint-36 验收记录](docs/acceptance/V1.7-checkpoint-36.md)。

## Agent 运行控制

流式调查请求支持 `Idempotency-Key` 和可选 `timeoutMs`。同一 Incident 使用相同键重试时返回原 run，不重复生成报告或处置提案；运行达到终态后前端清理该键，下一次人工运行会创建新 run。

```bash
AGENT_EXECUTION_TIMEOUT=60s
AGENT_MAX_EXECUTION_TIMEOUT=5m
AGENT_CORE_POOL_SIZE=2
AGENT_MAX_POOL_SIZE=4
AGENT_QUEUE_CAPACITY=50
AGENT_RECOVERY_ENABLED=true
AGENT_RECOVERY_DELAY=5000
AGENT_RECOVERY_BATCH_SIZE=100
```

运行先持久化为 `QUEUED`，随后进入 `RUNNING`。终态包括 `COMPLETED / PARTIAL / FAILED / CANCELLED / TIMED_OUT / QUEUE_REJECTED`。外部调用若不能立即响应线程中断，仍受 Provider 连接/读取超时约束，并在下一个安全执行边界完成取消或超时落库。实例退出留下的活动 run 会在 `deadline_at` 后由存活实例以行锁结算，并写终态事件、Incident 时间线和审计。当前不自动重放已中断工具，不声称跨节点续跑或 exactly-once 执行。

## Runbook 知识库与检索评测

管理员或运维经理可粘贴 Markdown，或上传最大 5 MB 的 `.md/.markdown/.pdf` 文件。相同 `stableKey + contentHash` 幂等复用；内容变化生成新版本并将旧版本标为 `SUPERSEDED`，历史引用不会被覆盖。PDF 当前只处理最多 200 页的可提取文本，不做扫描件 OCR。

检索先按当前登录角色过滤文档 ACL，再运行 BM25。若当前模型索引完整且 Provider 可用，系统追加余弦向量排序，并用 RRF（`rankConstant=60`）融合两个名次列表；RRF 不直接混合量纲不同的 BM25 原始分和余弦分。`AUTO / BM25 / HYBRID` 三种模式便于在线对照，返回实际执行引擎、词法/向量名次、索引覆盖率和降级 warning。

固定集已从 3 条扩充到 13 条种子改写查询，覆盖 Redis、接口延迟、认证、Kafka、MySQL 和 Kubernetes。当前默认离线演示中，`LEGACY_CONTAINS_V1` Recall@3/MRR/NDCG@3 为 0，`BM25_LOCAL_V1` Recall@3 为 1、MRR 为 0.961538、NDCG@3 为 0.971610、首位稳定引用命中率为 0.923077。Hybrid 只在真实索引完整并成功跑完全部查询时计分；无 Key 的默认演示会显示 unavailable。受控 `EmbeddingModel` 测试替身只验证索引、融合和降级契约，不冒充真实模型质量结论。

Runbook 页面保留原版/BM25/Hybrid 当前对照，并列出最近 12 次持久化固定集评测：运行时间、实际引擎、数据集版本、查询/qrel 分母及四项质量指标。只有数据集版本与引擎都相同的相邻运行才显示 Recall@3 变化；历史缺失指标保持 `N/A`。这不是从少量选择性人工反馈推算的生产命中率。

控制台和 Agent 的真实检索会保存查询、角色、请求/实际引擎、向量状态、耗时与返回结果快照；离线评测调用不记入查询日志，避免评测流量污染真实样本。查询本人只能评价快照中实际返回的文档，管理员或运维经理不能复核自己的判断，并以版本号阻止并发覆盖。待办只向复核人展示查询和当时的标题、摘要、引用，不暴露提交人身份、原始等级或评论；复核人独立给出 0–3 级，复核等级作为最终 qrel 等级，达到 2 才生成 `HUMAN_JUDGMENT` case。系统统计精确一致率、相差不超过一级的比例和线性加权 Cohen's kappa；拒绝样本及没有第二评分的历史记录不混入统计。评测时再把相同查询的多个相关文档聚合成 qrels，同一 query-document 的多个最终等级取平均，避免重复计权。当前闭环证明数据治理流程，不代表已经积累了生产规模标注。

查询文本和结果快照在写入数据库前统一屏蔽密码、Token、Authorization、邮箱和完整 IPv4，评分评论与复核备注也经过同一清洗。默认保留 30 天，每日定时按批处理到期记录；清理会把查询正文、哈希、结果 JSON 和查询人擦成不可逆墓碑，将尚未完成的复核自动拒绝并写入审计。已经晋级的 qrel 复制了脱敏查询、稳定文档键和最终等级，因此快照清理不会破坏后续评测。可通过 `RUNBOOK_RETRIEVAL_RETENTION`、`RUNBOOK_RETRIEVAL_CLEANUP_BATCH_SIZE` 和 `RUNBOOK_RETRIEVAL_CLEANUP_CRON` 调整策略。

## 关键接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/auth/login` | 登录并签发 JWT |
| GET | `/api/v1/dashboard` | 运行指标总览 |
| POST | `/api/v1/alerts/intake` | 接入告警并执行去重/聚合 |
| POST | `/api/v1/integrations/alertmanager/webhook` | 接收专用密钥保护的 Alertmanager v4 批量 webhook，返回逐项接入结果 |
| GET | `/api/v1/integrations/alertmanager/rejections` | 分页查询脱敏拒绝台账，支持 `OPEN/SUCCEEDED` 筛选 |
| POST | `/api/v1/integrations/alertmanager/rejections/{id}/replay` | 用短租约受控重放，成功/失败写审计 |
| GET | `/api/v1/incidents/{id}` | Incident、告警、时间线与报告 |
| POST | `/api/v1/incidents/{id}/transitions` | 受状态机与乐观锁保护的流转 |
| POST | `/api/v1/incidents/{id}/investigations` | 运行可追踪 Agent 调查并生成报告 |
| POST | `/api/v1/incidents/{id}/investigations/stream` | 接收幂等键与可选超时预算，返回持久化 SSE 事件 |
| GET | `/api/v1/incidents/{id}/agent-runs` | 查询工具级调查轨迹 |
| GET | `/api/v1/agent-runs/{runId}/events?after={eventId}` | 按事件游标回放调查过程 |
| GET | `/api/v1/agent-runs/{runId}/events/stream?after={eventId}` | 有界 SSE 续订，Redis 唤醒及数据库补读；支持 Last-Event-ID |
| POST | `/api/v1/agent-runs/{runId}/cancel` | 显式取消排队中或运行中的调查 |
| GET | `/api/v1/incidents/{id}/remediation-proposals` | 查询 Incident 的受控处置提案 |
| POST | `/api/v1/remediation-proposals/{id}/reviews` | 独立批准或拒绝高风险提案 |
| GET/POST | `/api/v1/incidents/{id}/postmortem` | 读取或从已恢复 Incident 的脱敏证据生成复盘草稿 |
| PATCH | `/api/v1/postmortems/{id}` | 以乐观锁更新复盘五类正文 |
| POST | `/api/v1/postmortems/{id}/submit` | 校验正文和行动项后提交独立复核 |
| POST | `/api/v1/postmortems/{id}/reviews` | 管理员/运维经理发布或退回复盘，禁止提交人自审 |
| POST/PATCH | `/api/v1/postmortems/{id}/follow-ups`、`/api/v1/postmortem-follow-ups/{id}` | 创建或更新带负责人、期限和版本的防复发行动项 |
| POST | `/api/v1/postmortem-follow-ups/{id}/complete` | 负责人或管理角色完成行动项并写入证据链 |
| GET | `/api/v1/analytics/incidents` | 按日期/严重等级读取 MTTA、MTTM、MTTR、样本数、服务级拆分、分布、慢事故和当前行动项摘要 |
| GET | `/api/v1/slo/objectives` | 读取服务 SLI、错误预算和三档多窗口燃烧率；外部不可用时显式返回状态 |
| PATCH | `/api/v1/slo/objectives/{id}` | 管理角色以乐观锁修改目标、滚动窗口和 PromQL 模板并写审计 |
| GET | `/api/v1/slo/objectives/{id}/versions/{version}/prometheus-rules` | 当前活跃管理角色导出捕获版本的规则与SHA256；旧版本/停用409，不自动部署 |
| GET | `/api/v1/postmortem-follow-ups` | 按本人/全部、完成状态、负责人确认状态与逾期筛选跨 Incident 行动项 |
| POST | `/api/v1/postmortem-follow-ups/escalations/run` | 管理员/运维经理按业务日期幂等生成逾期升级事实 |
| POST | `/api/v1/postmortem-follow-ups/{id}/notification/retry` | 管理员/运维经理重试仍开放的失败外部提醒并写审计 |
| GET | `/api/v1/runbooks/evaluations/history?limit=12` | 倒序读取有界固定集评测历史，保留数据集版本和实际引擎口径 |
| GET | `/api/v1/problems/recurrence-candidates` | 按窗口查询精确指纹复发候选、独立事故分母、信号总量和治理状态 |
| GET/POST | `/api/v1/problems` | 查询 Problem 台账，或由管理角色幂等登记候选并固化 Incident 关联 |
| GET/PATCH | `/api/v1/problems/{id}` | 读取或以乐观锁更新 Problem、已知错误和解决结论 |
| GET | `/api/v1/observability/providers` | 查询指标/日志 Provider 与熔断状态 |
| GET | `/api/v1/runbooks/search?q={query}&topK=3&mode=AUTO` | 按角色过滤并返回 BM25/Hybrid 实际引擎、排名轨迹、降级说明和稳定引用 |
| POST | `/api/v1/runbooks/searches/{searchId}/judgments` | 查询本人对快照返回文档提交 0–3 级相关性判断 |
| GET | `/api/v1/runbooks/judgments/pending` | 管理员/运维经理读取排除本人、隐藏原始评分的待复核快照 |
| GET | `/api/v1/runbooks/judgments/agreement` | 读取双评分样本数、精确/相邻一致率和线性加权 κ |
| POST | `/api/v1/runbooks/judgments/{id}/reviews` | 提交复核评分并批准/拒绝；最终等级 ≥ 2 才进入评测集 |
| GET | `/api/v1/runbooks/searches/retention` | 管理员/运维经理读取快照保留期、活跃/到期/已擦除数量和脱敏字段计数 |
| POST | `/api/v1/runbooks/searches/retention/purge` | 按保留策略幂等清理一批到期快照并记录审计 |
| GET | `/api/v1/runbooks/{stableKey}/versions` | 查询可访问的不可变版本历史 |
| POST | `/api/v1/runbooks/imports/markdown` | 管理员/运维经理提交不可变待审 Markdown；不直接发布 |
| POST | `/api/v1/runbooks/imports/file` | 管理员/运维经理上传 Markdown/PDF 为待审候选 |
| GET | `/api/v1/runbooks/publications?status=PENDING_REVIEW` | 管理角色按状态先过滤再读取最早200条审核台账，暴露总数与截断 |
| GET | `/api/v1/runbooks/publications/{id}` | 管理角色核对候选正文、ACL、审核版本及发布基线 |
| POST | `/api/v1/runbooks/publications/{id}/decisions` | 独立批准/拒绝或本人撤回；expectedVersion必填且非负（显式0合法，缺失/null为400），绑定请求键和说明，不自动改基线 |
| POST | `/api/v1/runbooks/evaluations` | 运行并保存固定检索评测 |
| GET | `/api/v1/runbooks/evaluations/latest` | 读取最近一次评测 |
| GET | `/api/v1/runbooks/semantic-index` | 查询当前模型的向量覆盖率和最近构建状态 |
| POST | `/api/v1/runbooks/semantic-index/rebuild` | 管理员/运维经理幂等、原子重建向量索引 |
| GET/POST | `/api/v1/assistant/sessions` | 查询或创建持久化会话 |
| POST | `/api/v1/assistant/sessions/{id}/messages` | 同步JSON对话；可选Idempotency-Key，完成同键同问题重放原答案 |
| POST | `/api/v1/assistant/sessions/{id}/stream` | SSE多轮对话；与同步入口共享键/容量/预算，仍是完成后的协议分块 |
| GET | `/api/v1/assistant/sessions/{id}/request` | 本人以Idempotency-Key读取QUEUED/运行及终态；404不证明无接纳中请求，不自动换键重发 |
| POST | `/api/v1/assistant/sessions/{id}/request/cancel` | 本人以原Idempotency-Key取消QUEUED/RUNNING，一次审计；完成先提交则保持原答案 |
| GET | `/api/v1/assistant/sessions/{id}/export` | 导出 Markdown 对话记录 |
| GET | `/api/v1/cmdb/topology` | 服务依赖拓扑 |
| GET | `/api/v1/on-call/current` | 当前值班人 |
| GET | `/api/v1/on-call/roster` | 班次窗口、可用计划/负责人与数据库时间；最多 31 天/200 条 |
| GET | `/api/v1/on-call/coverage` | 指定计划的有效覆盖/缺班、胜出与遮盖班次；最长31天，超过1000源班次拒算 |
| GET/POST | `/api/v1/on-call/handoffs` | 以计划、scope=ALL/MINE、status筛选后有界读取；本人以 UUID/源班次版本申请定向接班，页面支持冻结草稿与同键恢复 |
| GET | `/api/v1/on-call/handoffs/{id}/coverage` | 单次快照读取原接受事实、实际覆盖行与独立管理撤销台账；接班台账详情入口 |
| POST | `/api/v1/on-call/handoffs/{id}/coverage/revoke` | 当前活跃管理角色以捕获的双版本/UUID/理由撤销未结束覆盖，原接受不改，同事务双审计；界面原意图手动恢复重试 |
| POST | `/api/v1/on-call/handoffs/{id}/decisions` | 指定接班人接受/拒绝，申请人撤回；版本化、同事务覆盖与审计 |
| POST | `/api/v1/on-call/shifts` | 管理角色创建普通/覆盖班次，同层重叠返回 409 |
| POST | `/api/v1/on-call/shifts/{id}/cancel` | 带版本/原因取消班次，保留历史与审计 |
| GET/POST | `/api/v1/on-call/rotations` | 查询/创建有序轮转；列表有截断标志，创建限管理角色 |
| GET | `/api/v1/on-call/rotations/{id}/slots` | 查询时段生成/受阻台账、取消事实与成员当前资格 |
| POST | `/api/v1/on-call/rotations/{id}/state` | 以版本/原因暂停或恢复续排，不撤销既有班次 |
| POST | `/api/v1/on-call/rotations/scan` | 管理角色立即扫描；每条规则独立事务、失败隔离 |
| GET | `/api/v1/on-call/escalations` | 最近 50 步站内升级记录 |
| POST | `/api/v1/on-call/escalations/scan` | 管理员/运维经理立即扫描到期未确认 Incident |
| GET | `/api/v1/audit-logs` | 操作审计 |

Swagger UI: [http://localhost:9900/swagger-ui/index.html](http://localhost:9900/swagger-ui/index.html)

## 测试

```bash
cd web && npm test && npm run build
cd .. && ./mvnw test

# 需要本机 Docker；在真实 MySQL 8.4 上执行 V1-V27 迁移和关键业务链路
./mvnw -Dopspilot.mysql.it.enabled=true -Dtest=MySqlCompatibilityIntegrationTest,MySqlOnCallRoutingSnapshotIntegrationTest,MySqlOnCallRotationIntegrationTest,MySqlOnCallCoverageIntegrationTest,MySqlOnCallHandoffIntegrationTest test

# 从最新前端源码打包后，真实浏览器门禁自行启动并清理隔离内存 JAR
./mvnw -DskipTests package
npm ci --prefix scripts/browser
node scripts/browser/node_modules/playwright/cli.js install --with-deps chromium
npm test --prefix scripts/browser
node scripts/verify-oncall-browser-ci.cjs
```

测试覆盖：

- Incident 合法/非法状态流转。
- 登录、JWT 与审计角色 403。
- 乐观锁版本冲突。
- 指纹告警的首次创建与重复压缩。
- Alertmanager webhook 的独立鉴权、批量上限、非法 JSON、严重度映射、部分失败隔离、重试幂等和 firing/resolved 生命周期；拒绝快照脱敏、动态 `endsAt` 去重、重复投递租约保护、只读审计与 CMDB 修复后并发重放。
- 真实 Alertmanager 0.34.1 容器通过专用鉴权投递 firing、resolved 与混合好坏项；MySQL 结果、单条恢复时间线、拒绝快照脱敏均由 [Run 35853706878](https://github.com/Trigger726/OnCall-Agent/actions/runs/35853706878) 的独立门禁验证。
- 总览、CMDB 拓扑和 Incident 详情接口。
- OnCall 会话持久化、Incident 上下文、SSE 完成事件、证据引用和跨用户隔离。
- Agent 调查运行落库、9 步执行轨迹、六类数据源、Incident/OnCall 同源回读和运行证据引用。
- 18 条成功调查事件的 SSE 输出、持久化顺序和 `after` 游标回放。
- 调查幂等重试、排队取消、运行中取消竞态、超时预算、线程中断和队列拒绝终态。
- SSE 客户端断开隔离，以及单 worker/单队列槽位下的多请求饱和和排队取消。
- 高风险处置提案生成、角色限制、自批禁止、审批版本冲突和独立账号审批。
- Provider 状态、Prometheus/Loki 关闭时的本地路由、日志敏感字段脱敏。
- Provider 重试、熔断、半开探测和恢复状态机。
- Prometheus instant query、Loki range query 的真实 HTTP 请求/响应契约，以及外部日志失败后的路由降级。
- Runbook 中文/英文分词、Markdown 分块、PDF 文本抽取、内容幂等、不可变版本、角色 ACL、稳定引用和 13 条新旧检索对照评测。
- 向量索引内容指纹幂等、RRF 稳定融合、完整覆盖门槛、显式 BM25 降级，以及 Provider 失败不覆盖旧索引。
- 真实检索快照、结果范围校验、查询归属、0–3 级相关性判断、角色限制、自审禁止、复核版本冲突、正/负样本分流和离线评测流量隔离。
- 同一查询多相关文档聚合、分级 qrels、重复标注平均、查询/qrels 双计数，以及 Recall@3、MRR、NDCG@3 的精确回归值。
- 复核评分必填、原始评分/提交人盲化、最终等级晋级、拒绝样本隔离、线性加权 κ 公式及空/无类别变化边界。
- 检索查询/结果/评论入库前脱敏、保留期权限、过期待办自动拒绝、批量清理幂等、擦除审计，以及原始快照清理后 qrel 继续可用。
- 复盘仅在 Incident 恢复后生成、证据快照写前脱敏、草稿完备门禁、不可自审、退回再提交、发布冻结、父子版本冲突、行动项责任权限和时间线/审计留痕。
- MTTA/MTTM/MTTR 均值、中位数、独立分母、日期/严重等级筛选、缺失/负时长排除、慢事故下钻和 SPA 深链。
- 跨 Incident 行动项筛选、截止当天边界、逾期天数、扫描角色限制、唯一升级事实、重复扫描幂等和完成后关闭。
- 跨 Incident 精确指纹复发与单事故告警噪声分离、候选可解释口径、Problem 并发/重复创建幂等、生命周期字段门禁、乐观锁、权限审计、未来 Incident 自动关联和解决后复发。
- MySQL 8.4 Testcontainers：Flyway V1-V26、中文数据、幂等复合唯一索引、Runbook BM25、完整 9 步/18 事件调查、复盘发布、逾期扫描/行动项确认与完成，以及 Problem、SLO、Alertmanager、值班升级双扫描、排班冲突/取消与轮转并发/故障隔离，已在真实 MySQL 远端门禁通过。

checkpoint 46/47 的历史默认后端套件发现 169 项测试：135 项在 UTC/上海时区分别执行通过，34 项 Docker（MySQL/Redis/双 JVM）条件测试默认跳过。checkpoint 46 前端新增 8 项轮转契约/状态测试，共 21/21，通过生产构建与最新 JAR 的桌面/390px 实际创建、暂停、旧版本 409、后台补班、新 P1 路由、取消不复活和历史保护。代码 `d2541c7` 的 [Run 36268078605](https://github.com/Trigger726/OnCall-Agent/actions/runs/36268078605) 十一项 CI 全绿（4 分 1 秒），直接日志确认真实 MySQL V26/27 项执行/零跳过、远端双时区回归和前端 21 项/生产构建。checkpoint 47 将两份脚本接入独立真实浏览器 CI：`npm ci --prefix scripts/browser`、安装匹配 Chromium 后运行 `node scripts/verify-oncall-browser-ci.cjs`，自动启动唯一内存隔离 JAR、拒绝占用端口并清理自有进程；8 项生命周期测试与本地完整流程通过，代码 `12578c0` 的 [Run 36269425451](https://github.com/Trigger726/OnCall-Agent/actions/runs/36269425451) 十二项全绿；Linux Chromium 桌面/390px 流程、双时区 H2 与真实 MySQL 27 项零跳过均通过，远端 ZIP/JSON/截图已下载核验。旧界面截图与原始 OnCall 归档分支继续保留，新旧对照见 [checkpoint 46](docs/acceptance/V1.7-checkpoint-46.md)，当时工程验收见 [checkpoint 47](docs/acceptance/V1.7-checkpoint-47.md)。

checkpoint 45 新增 10 项轮转共享场景与 1 项真实定时任务测试；代码 `2fc082e` 的 [Run 36266295563](https://github.com/Trigger726/OnCall-Agent/actions/runs/36266295563) 十一项 CI 全部成功（4 分 8 秒），解码日志确认 V26 在真实 MySQL 8.4 上迁移成功，轮转 10 项 + 路由快照 8 项 + 兼容性 9 项共 27 项执行、零跳过；远端双时区回归与前端生产构建也通过。

checkpoint 44 以实际接入/扫描入口复现已提交取消仍命中旧快照，显式 READ_COMMITTED 修复；八项共享场景覆盖取消/新覆盖/计划与策略停用/账号及角色变化，以及告警、Incident、升级、通知、时间线、审计原子回滚。代码 `c85603d` 的 [Run 36264417669](https://github.com/Trigger726/OnCall-Agent/actions/runs/36264417669) 十一项 CI 全部成功（4 分 7 秒），直接日志确认 MySQL 新套件 8 项加兼容套件 9 项全部执行、零跳过；上一轮代码 `f36ed94` 的 [Run 36262966874](https://github.com/Trigger726/OnCall-Agent/actions/runs/36262966874) 已验证 V25 班次并发与十一项 CI（4 分 19 秒），V24 的 [Run 36260824938](https://github.com/Trigger726/OnCall-Agent/actions/runs/36260824938) 也继续保留。CI 的 H2 门禁同时覆盖 UTC 打包与上海时区回归，MySQL 门禁执行兼容性、路由快照与轮转三套测试并上传 Surefire 报告。Flyway 9.22.3 的 MySQL 支持上限提醒及 `upload-artifact@v4` 的 Node.js 20 废弃提醒仍需处理。GitHub Actions 分离前端、H2/JAR、MySQL、Alertmanager/Prometheus 规则、Redis/双 JVM、Trace/Tempo、通知容器和镜像启动等十一项门禁。详细分阶段证据见 [docs/acceptance/README.md](docs/acceptance/README.md)。

## 目录

```text
src/main/java/org/trigger/opspilot/
  alert/          告警接入、去重和聚合
  incident/       Incident 状态机与时间线
  investigation/ Agent 编排、只读工具、执行轨迹、规则结论和可选 AI 摘要
  remediation/   高风险处置提案、独立审批与并发控制
  postmortem/    无责复盘、独立发布、防复发行动项与逾期升级
  analytics/     事故响应指标、样本口径、严重等级分布与慢事故下钻
  problem/       重复事故候选、Problem 生命周期、已知错误与关联证据
  observability/ Metrics/Logs Provider、Prometheus/Loki 适配、可靠性保护和日志脱敏
  runbook/       文档版本/ACL、BM25/向量 RRF、检索快照、相关性复核和评测
  assistant/      多轮会话、Incident 上下文、SSE 与导出
  cmdb/           资源台账和依赖拓扑
  oncall/         排班与升级策略
  security/       JWT 和 RBAC
  audit/          操作审计
web/              Vue 3 运维控制台
deploy/           Prometheus 配置
docs/             架构、演示、面试材料和阶段验收报告
```

## 项目来源

OpsPilot 是在原 OnCall AI Agent 原型上进行的独立重构。重构保留并升级了原版多轮对话能力，同时补齐 Incident、CMDB、状态机、值班和审计闭环。原始版本保存在 Git 分支 `archive/oncall-original-2026-08-19`，新实现位于 `feat/opspilot-v1`，两者可以独立查看和演示。

License: MIT
