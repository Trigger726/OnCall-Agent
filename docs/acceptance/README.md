# OpsPilot 验收记录

本目录用于保存 OpsPilot 的阶段性验收报告。项目按“完成一组改动、形成一组证据、写入一份报告”的节奏持续迭代，报告中的结论只覆盖已经获得直接证据的范围。

76双节点助手停止围栏本地限定通过：[报告](V1.7-checkpoint-76.md)。两个独立JVM/同一新建H2 SQL/真实模型HTTP，四场景两次PASS；A执行时B取消/清空/撤销，实际HTTP与原worker不依赖旧Provider放行，旧74反例保留。增加CI门禁但自身提交远端待验，不借75全绿，不外推MySQL原生HTTP、跨机器HA或生产容量。

75默认助手原生流本地与自身远端限定通过：[报告](V1.7-checkpoint-75.md)。55d80e5的[Run37128573740](https://github.com/Trigger726/OnCall-Agent/actions/runs/37128573740)十五作业success，四ZIP源SHA/官方摘要/实际下载及原门禁重放已核验；54原生契约、双时区各513发现/407执行/106条件跳过、真实MySQL99/五池关闭、Linux原十三页面/八助手UI/五原生UI和五认证runner已验。真实模型结束前可见临时片段，SQL提交后才发布答案ID；取消/超时关闭原HTTP、worker可复用，截断不保存半个答案。前端107/生命周期17、新旧27图与失败保留。原生MySQL HTTP矩阵、跨节点、慢消费者/生产容量和整体目标继续。

历史74底层适配器的[Run37117383000](https://github.com/Trigger726/OnCall-Agent/actions/runs/37117383000)十五作业success，新增原生工件17项的源SHA/官方摘要/实际ZIP/XML/故障注入日志已独立核验，其他工件尚未独立重放，见[74报告](V1.7-checkpoint-74.md)。当时默认端点尚未接入，75另行补齐。以下历史检查点的“当前/尚未”等边界均对应当时，不替代75的新证据。

73助手界面本地与自身远端限定通过：[报告](V1.7-checkpoint-73.md)。79e4d5e的[Run37114973597](https://github.com/Trigger726/OnCall-Agent/actions/runs/37114973597)十五作业success，三ZIP源SHA/摘要、原MySQL门禁99/五池关闭、Linux八界面流程/原十三页面/五认证runner与完整日志已独立核验。冻结原键/问题、显式取消、手动恢复、真实503和晚200身份围栏成立；前端103/生命周期17/audit0及新旧Demo保留。默认对话仍完整回答后分块，原生token流/生产规模与整体目标继续。

72后端限定验收已闭环：390dd7c的[Run37111174821](https://github.com/Trigger726/OnCall-Agent/actions/runs/37111174821)十五作业success，真实MySQL99/V33/五池关闭、Linux取消/丢响应/排队SIGKILL及两ZIP源SHA/摘要/原门禁重放已独立核验，见[72报告](V1.7-checkpoint-72.md)。该提交没有默认页面稳定键/取消按钮；73另补界面，旧JAR/Demo/失败保留，整体目标继续。

71自己的bd508bd已核对[Run37108835199](https://github.com/Trigger726/OnCall-Agent/actions/runs/37108835199)十五作业success，真实MySQL96/V32/五池关闭、Linux幂等三JVM强制终止与两ZIP源SHA/实际摘要/原门禁重放通过，见[V1.7-checkpoint-71.md](V1.7-checkpoint-71.md)。不借旧96证明72的V33/新取消事务99项。

70自己的5cae132已核对[Run37096366762](https://github.com/Trigger726/OnCall-Agent/actions/runs/37096366762)十五作业success，真实MySQL93零失败错误跳过/五池关闭及认证/助手两ZIP源SHA/实际摘要/原门禁重放通过，见[V1.7-checkpoint-70.md](V1.7-checkpoint-70.md)。不借旧93证明71的V32/新三事务。

69自己的45247a8已核对[Run37095079404](https://github.com/Trigger726/OnCall-Agent/actions/runs/37095079404)：十三success/MySQL failure/最终容器skipped，不能标全绿。认证ZIP源SHA/摘要及Linux三结果/四停机日志通过；真实MySQL93中CHECK异常类别fixture失败，另四个助手事务执行通过；70修正为精确SQL错误/约束名并继续检查回滚，不放宽门禁。见[V1.7-checkpoint-69.md](V1.7-checkpoint-69.md)。

68自己的40e0c2f已补[Run37092997128](https://github.com/Trigger726/OnCall-Agent/actions/runs/37092997128)十五作业success；认证/助手实际生产HTTP七次/四份JAR日志、MySQL88一般兼容回归/五池关闭与两ZIP源SHA摘要及精确原门禁重放已核验。后来发现的更晚提交/同步返回空窗由69另列，旧Demo/首连接Future三秒超时仍保留，不借旧绿灯外推新五事务矩阵。见[V1.7-checkpoint-68.md](V1.7-checkpoint-68.md)。

67已补客户端自身远端验收：3b6247b的[Run37090872485](https://github.com/Trigger726/OnCall-Agent/actions/runs/37090872485)十五作业completed/success，浏览器ZIP源提交/官方digest与实际SHA256相符，Linux十三脚本退出码0、三入口真实logout/旧401/新admin凭证保留与服务端200已独立核对；九PNG实际存在，本次工件重放未重新目视截图。c903d59后端真实MySQL88/五池关闭及三工件定向重放原结论保留，跨节点、Trace偶发控制失败与完整目标仍继续。见[V1.7-checkpoint-67.md](V1.7-checkpoint-67.md)。

最新阶段报告：[V1.7-checkpoint-66.md](V1.7-checkpoint-66.md)：本人安全页/错误当前密码与旧401竞态/深链刷新，前端72/audit0、双时区375发现282执行93条件跳过、十二实页脚本与同库跨JVM本地/远端通过。真实提交后丢响应只一次POST、新凭证恢复，旧Demo/本地七图/远端五图/失败保留；7705b8d的Run37058813999十五作业success，真实MySQL86零跳过/五池关闭、四ZIP源SHA/摘要与重放已核验，既有SSE/任务再授权与整体目标继续。

最新服务端验收：[V1.7-checkpoint-65.md](V1.7-checkpoint-65.md)：0f21f68的Run37055729127十五作业success，真实MySQL86零跳过/五池关闭、Linux跨JVM及三ZIP源SHA/digest/重放已验。本人改密/持久化全部会话撤销、严格版本与事务审计，真实字节截断/H2延迟落盘失败已修复；双时区374/281/93。该提交未交付自助UI，66另补；既有SSE/后台任务再授权和整体目标继续，旧Demo/失败均保留。

最新限定验收：[V1.7-checkpoint-64.md](V1.7-checkpoint-64.md)：真实旧构建栈耗尽/vue-tsc隐性exit0错误已复现，仅brace-expansion2.1.4→2.1.7、4项隔离回归与全树官方audit含dev门禁；0e76296的[Run37049527001](https://github.com/Trigger726/OnCall-Agent/actions/runs/37049527001)十四作业success，前端62/全量audit0/双时区338发现255执行83条件跳过/MySQL76/十一脚本/五ZIP源SHA摘要与回放通过。新旧实页/旧JAR/首次环境失败保留，四静态文件逐字节一致。非线上远程利用或全项目安全验收，完整目标继续。

最新限定验收：[V1.7-checkpoint-63.md](V1.7-checkpoint-63.md)：显式可选Collector原生告警受控进入Incident，未知库存拒绝/显式修复、native重复无写及两条原ID恢复/各一次时间线通过，Incident仍OPEN无人工回执。7e81395的[Run37045120031](https://github.com/Trigger726/OnCall-Agent/actions/runs/37045120031)十四作业success，真实MySQL76/双时区338/255/83/前端58/十一脚本、四ZIP摘要与原始数据重放通过。两次失败/历史Demo保留，生产容量/磁盘卷故障/依赖安全修复与完整目标继续。

最新限定验收：[V1.7-checkpoint-62.md](V1.7-checkpoint-62.md)：默认十消费者SIGKILL/重建后队列13→13/在途10→10，原图及10/10探针恢复；小队列真实拒绝/12个200后持续404、原生双告警与独立恢复正对照通过。4cdda31的[Run37039755121](https://github.com/Trigger726/OnCall-Agent/actions/runs/37039755121)十三作业success，真实MySQL76/双时区338/255/83/前端58/十一脚本、三ZIP摘要及六图/逐ID/MySQL重放已核对。三次远端失败/历史Demo保留；Collector反馈未到Alertmanager/Incident，生产容量/磁盘/卷故障仍待验，整体目标继续。

最新限定验收：[V1.7-checkpoint-61.md](V1.7-checkpoint-61.md)：Collector SIGKILL/不同容器重建的旧内存队列7→0、原Trace404已复现；独立卷/file_storage/fsync版6→6、六工具及两跨JVM分支完整恢复。5ed5a9f的[Run36999165718](https://github.com/Trigger726/OnCall-Agent/actions/runs/36999165718)十三作业success，15损坏图拒绝、真实MySQL76/双时区338/255/83/58前端/十一脚本及最终容器通过；三工件摘要和四图/MySQL重放已验。首失败/旧配置/JAR/Demo保留，生产容量与磁盘/队满未验，整体目标继续。

最新限定验收：[V1.7-checkpoint-60.md](V1.7-checkpoint-60.md)：签发身份绑定与时间极值500→401。b1a0945的[Run36996179284](https://github.com/Trigger726/OnCall-Agent/actions/runs/36996179284)十三作业success，真实MySQL76零跳过/八场景/五池干净关闭、双时区338/255/83、58前端与Linux十一脚本及最终容器通过；工件摘要/CLI重放/实页已验。实际新旧JAR12请求与异常12→0、两份旧JAR/首次失败/历史Demo保留；整体目标继续。

最新限定验收：[V1.7-checkpoint-59.md](V1.7-checkpoint-59.md)：停用后的旧JWT读写200首失败已复现并修正为401，活跃降权403不变。aa76689的[Run36992121756](https://github.com/Trigger726/OnCall-Agent/actions/runs/36992121756)十三作业success，真实MySQL73零跳过/五池干净关闭、五个命名HTTP场景/真实审计IP、双时区291/211/80、58前端与Linux十脚本已验；工件摘要/CLI重放通过。首次MySQL上下文失败、旧JAR与历次Demo保留，永久撤销与既有任务再授权仍待完善，整体目标继续。

最新限定验收：[V1.7-checkpoint-58.md](V1.7-checkpoint-58.md)：周期一致的SLO阈值、分数精度与版本绑定原生规则。bbe00f9的[Run36989009983](https://github.com/Trigger726/OnCall-Agent/actions/runs/36989009983)十三作业success，原生20场景/真实firing重复投递与resolved、双时区281/206/75、真实MySQL68零跳过/五池关闭、58前端及Linux十脚本已验；三个工件摘要、CLI重放和远端桌面/390px已核对，旧Demo保留。生产长期数据/低流量政策/自动部署未验收，整体目标继续。

前一契约验收：[V1.7-checkpoint-57.md](V1.7-checkpoint-57.md)：缺失/null审核版本六例拒绝、补显式0六成功；e98ee3a的Run36344164602十二CI作业success，双时区270/195/75、真实MySQL68零跳过/五池关闭、58前端与Linux九脚本已核验，ZIP摘要及MySQL CLI重放通过。旧JAR/失败/桌面手机保留。

前一基线：[V1.7-checkpoint-56.md](V1.7-checkpoint-56.md)：Runbook独立复核、发布基线与精确重放。8b5c95b的Run36343295826十二CI全绿，58前端、双时区264/189/75、真实MySQL68零跳过/五池关闭、Linux扩展九脚本与三工件摘要/重放/实图已验；通知503→204/同键/重复扫描0，最终容器烟测实际通过。eb47adb失败/403保留；未覆盖的缺失/null版本字段由57补，不等于全部JSON契约或整体目标完成。

最新限定验收：[V1.7-checkpoint-55.md](V1.7-checkpoint-55.md)：真实检索逐日趋势、完整独立复核子集Hit@K与清理后分母保持通过。`49c0161` 的 [Run36338448177](https://github.com/Trigger726/OnCall-Agent/actions/runs/36338448177) 十二作业success，真实MySQL63零跳过/五池干净关闭、前端50项、双时区各250发现/180执行/70条件跳过、八脚本与10项生命周期测试通过；新旧/远端桌面手机、工件摘要与MySQL CLI重放已验。原Demo保留，不外推生产质量，整体目标仍进行中。

前一业务阶段：[V1.7-checkpoint-54.md](V1.7-checkpoint-54.md)：覆盖详情/独立管理撤销界面、账号隔离冻结意图恢复与冲突锁定限定通过。`ad701b7` 的 [Run36335732140](https://github.com/Trigger726/OnCall-Agent/actions/runs/36335732140) 十二作业success，前端47项、真实MySQL62零跳过/五池干净关闭、双时区各244发现/175执行/69条件跳过、七脚本通过；新旧/远端桌面手机与工件摘要/CLI重放已验。原Demo保留，非完整全球换班/整体目标验收。

后端基线：[V1.7-checkpoint-53.md](V1.7-checkpoint-53.md)：已接受接班覆盖详情/独立管理撤销后端限定通过；`055d77a` 的 [Run36330898471](https://github.com/Trigger726/OnCall-Agent/actions/runs/36330898471) 十二作业success，真实MySQL62项零跳过/五池干净关闭、双时区各244发现/175执行/69条件跳过、前端39项与六脚本通过，工件摘要/本地重放已验。当时未交付的撤销界面由54继续补，原阶段报告结论保留。

最新工程验收：[V1.7-checkpoint-52.md](V1.7-checkpoint-52.md)：真实MySQL生命周期失配已修复；`276b57b` 的 [Run36329512979](https://github.com/Trigger726/OnCall-Agent/actions/runs/36329512979) 十二作业success，52项零跳过、五池逐类关闭、完整日志拒绝项0、无强制fork退出，12项门禁测试/双时区/39项前端/浏览器五脚本通过。工件摘要及首失败/成功本地重放已验，原Demo与失败证据保留，整体目标仍进行中。

最新限定验收：[V1.7-checkpoint-51.md](V1.7-checkpoint-51.md)：定向接班页面、冻结恢复、双方决定与409锁定通过；`49c221c` 的 [Run 36328347791](https://github.com/Trigger726/OnCall-Agent/actions/runs/36328347791) 十二作业success，前端39项、双时区、真实MySQL52项断言与Linux五脚本通过，新旧/远端桌面手机证据已归档。当时发现的MySQL后台任务泄漏/fork强制退出已由52修复，51报告保留原结论；非完整换班验收。

前一阶段：[V1.7-checkpoint-50.md](V1.7-checkpoint-50.md)：接班台账本人/状态先过滤再截断，旧待办遗漏有失败对照；`a87265a` 的 [Run 36326409469](https://github.com/Trigger726/OnCall-Agent/actions/runs/36326409469) 十二项全绿，真实MySQL52执行零跳过、双时区各224发现/165执行、原有桌面/手机与HTTP回归通过，ZIP摘要/JSON/截图已核验。当时未交付的接班UI与新旧实页Demo由检查点51补齐；历史报告保留原阶段结论。

前一阶段：[V1.7-checkpoint-49.md](V1.7-checkpoint-49.md)：定向接班后端已验，V27、本人权限/幂等/版本/行锁/事务覆盖与真实HTTP通过；修复代码 `eb3ef78` 的 [Run 36325182213](https://github.com/Trigger726/OnCall-Agent/actions/runs/36325182213) 十二项全绿，真实MySQL50项零跳过、双时区各220项发现/163项执行。当时尚未交付的页面/新旧Demo对照由51补齐，仍不等于完整换班验收；首轮SSE安全头竞态失败与修复已归档。

最新检查点：[V1.7-checkpoint-48.md](V1.7-checkpoint-48.md)：逐日日历、有效覆盖/缺班与取消后自动刷新通过；[Run 36291809846](https://github.com/Trigger726/OnCall-Agent/actions/runs/36291809846) 十二项全绿，前端29项、生命周期9项、双时区各189项发现/147项执行、真实MySQL35项零跳过、Linux桌面/390px与完整停机日志已核验。首次失败证据保留；原始Demo归档分支已上传。

上一工程检查点：[V1.7-checkpoint-47.md](V1.7-checkpoint-47.md)：真实浏览器 CI、自有进程隔离与证据上传已通过；[Run 36269425451](https://github.com/Trigger726/OnCall-Agent/actions/runs/36269425451) 十二项全绿，Linux/Chromium 桌面与390px流程、8 项生命周期测试、21 项前端、双时区回归与真实 MySQL 27 项通过，ZIP/JSON/截图已下载核验。

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
| V1.7 | 37：负责人确认接手与投递回执分离 | 通过（限本检查点），MySQL/HTTP/十一项 CI 与桌面/移动实页已验 | [V1.7-checkpoint-37.md](V1.7-checkpoint-37.md) |
| V1.7 | 38：待接手运营筛选与通知时间精度 | 通过（限本检查点），本地实页与远端 MySQL/十一项 CI 已验 | [V1.7-checkpoint-38.md](V1.7-checkpoint-38.md) |
| V1.7 | 39：草稿行动项的发布门禁 | 通过（限本检查点），本地 H2/JAR、远端 MySQL/十一项 CI 已验 | [V1.7-checkpoint-39.md](V1.7-checkpoint-39.md) |
| V1.7 | 40：固定集检索评测历史与口径隔离 | 通过（限本检查点），本地实页与远端 MySQL/十一项 CI 已验 | [V1.7-checkpoint-40.md](V1.7-checkpoint-40.md) |
| V1.7 | 41：Actuator 管理监听隔离 | 通过（限本检查点），本地 JAR 双端口与远端 MySQL/十一项 CI 已验 | [V1.7-checkpoint-41.md](V1.7-checkpoint-41.md) |
| V1.7 | 42：未确认 Incident 值班升级执行 | 通过（限本检查点），双时区/JAR HTTP 与十一项 CI 已验 | [V1.7-checkpoint-42.md](V1.7-checkpoint-42.md) |
| V1.7 | 43：有效班次维护与可审计取消 | 通过，双时区/HTTP/桌面手机及真实 MySQL/十一项 CI 已验 | [V1.7-checkpoint-43.md](V1.7-checkpoint-43.md) |
| V1.7 | 44：升级路由已提交状态可见性 | 通过，双时区/真实 MySQL 八场景与十一项 CI 已验 | [V1.7-checkpoint-44.md](V1.7-checkpoint-44.md) |
| V1.7 | 45：轮转规则与自动续排后端 | 后端已验；当时 UI 待验，已在 46 补齐 | [V1.7-checkpoint-45.md](V1.7-checkpoint-45.md) |
| V1.7 | 46：轮转管理与异常台账页面 | 通过（限本检查点），21 项前端/双时区/JAR/桌面手机及十一门禁已验 | [V1.7-checkpoint-46.md](V1.7-checkpoint-46.md) |
| V1.7 | 49：定向接班请求后端 | 部分通过，后端/MySQL/HTTP/十二门禁已验，接班UI待交付 | [V1.7-checkpoint-49.md](V1.7-checkpoint-49.md) |
| V1.7 | 50：接班台账筛选与待办完整性 | 部分通过，列表/MySQL/双时区/十二门禁已验，接班UI待交付 | [V1.7-checkpoint-50.md](V1.7-checkpoint-50.md) |
| V1.7 | 51：定向接班页面与故障重试 | 限定范围通过，十二作业/新旧Demo已验；当时MySQL错误由52修复 | [V1.7-checkpoint-51.md](V1.7-checkpoint-51.md) |
| V1.7 | 52：真实MySQL生命周期与日志门禁 | 本工程范围通过，十二作业/52项/五池关闭/工件重放已验 | [V1.7-checkpoint-52.md](V1.7-checkpoint-52.md) |
| V1.7 | 53：已接受接班覆盖撤销后端 | 后端限定通过，十二CI/真实MySQL62/六脚本/工件重放已验；当时未交付的页面由54补齐 | [V1.7-checkpoint-53.md](V1.7-checkpoint-53.md) |
| V1.7 | 54：覆盖详情与管理撤销界面 | 限定通过，十二CI/47前端/真实MySQL62/七脚本/新旧实页与工件重放已验 | [V1.7-checkpoint-54.md](V1.7-checkpoint-54.md) |
| V1.7 | 55：真实检索复核质量趋势 | 限定通过，十二CI/50前端/真实MySQL63/八脚本/新旧与远端实页、工件摘要与MySQL重放已验 | [V1.7-checkpoint-55.md](V1.7-checkpoint-55.md) |
| V1.7 | 56：Runbook独立复核发布 | 基线十二CI/真实MySQL68/扩展九脚本已验；缺失/null版本字段由57继续补 | [V1.7-checkpoint-56.md](V1.7-checkpoint-56.md) |
| V1.7 | 57：发布决定显式捕获版本 | 本范围通过，十二CI/真实MySQL68/扩展九脚本与工件已核验 | [V1.7-checkpoint-57.md](V1.7-checkpoint-57.md) |
| V1.7 | 58：版本绑定的SLO原生告警规则 | 限定通过，十三CI/原生20场景/真实链路/MySQL68/十脚本及新旧Demo | [V1.7-checkpoint-58.md](V1.7-checkpoint-58.md) |
| V1.7 | 59：停用账户的旧JWT入口资格 | 限定通过，十三CI/MySQL73/五场景/双时区/十脚本；首失败保留 | [V1.7-checkpoint-59.md](V1.7-checkpoint-59.md) |
| V1.7 | 60：签发身份绑定与畸形Token时间边界 | 限定通过，十三CI/MySQL76/八场景/十一脚本/新旧JAR与工件已验 | [V1.7-checkpoint-60.md](V1.7-checkpoint-60.md) |
| V1.7 | 63：Collector原生告警受控进入Incident | 限定通过，十四CI/同ID恢复/原始重放；两次失败与旧Demo保留 | [V1.7-checkpoint-63.md](V1.7-checkpoint-63.md) |

## 状态约定

最新轮转页面：[V1.7-checkpoint-46.md](V1.7-checkpoint-46.md)：有序创建、版本化暂停/恢复、异常/取消/资格台账与互相刷新已接入。前端 21 项、双时区后端、最新 JAR、1440px/390px 真实管理流程及可复跑脚本通过；代码 `d2541c7` 的 [Run 36268078605](https://github.com/Trigger726/OnCall-Agent/actions/runs/36268078605) 十一项全绿（4 分 1 秒），解码日志确认真实 MySQL 27 项执行/0 跳过。列表截断/成员不可用/部分失败的呈现夹具与真实 API 证据分开记录；浏览器未进入 CI，旧 Demo 不变。

最新轮转后端：[V1.7-checkpoint-45.md](V1.7-checkpoint-45.md)：有序规则、14 天幂等物化、冲突/资格台账、取消不复活、版本化暂停/恢复与真实自动续排；双时区各 169 项发现/135 项执行，前端 13 项及当时 UTC JAR 的 HTTP/后台/P1 路由通过。代码 `2fc082e` 的 [Run 36266295563](https://github.com/Trigger726/OnCall-Agent/actions/runs/36266295563) 十一项全绿（4 分 8 秒），解码日志直接确认真实 MySQL V26/27 项执行/0 跳过。当时缺少的轮转页面/浏览器对照已由 checkpoint 46 补齐，checkpoint 45 原报告保留阶段状态。

最新事务可见性：[V1.7-checkpoint-44.md](V1.7-checkpoint-44.md)：真实接入/扫描入口显式 READ_COMMITTED，旧快照误路由有失败证据；共享八项提交闸门与回滚场景已过 H2 与真实 MySQL，双时区各发现 148 项、执行 124 项、24 项 Docker 条件跳过，前端 13 项通过。[Run 36264417669](https://github.com/Trigger726/OnCall-Agent/actions/runs/36264417669) 十一项 CI 全绿，MySQL 日志确认 17 项执行/零跳过；页面和历史 Demo 不变。

最新班次维护：[V1.7-checkpoint-43.md](V1.7-checkpoint-43.md)：角色受控的普通/覆盖班次、同层重叠冲突、版本化软取消和历史窗口查询；本地双时区各 132 项发现/116 项执行、前端 13 项、最新 JAR 真实路由及桌面/390px 已验，原历史班次不变。[Run 36262966874](https://github.com/Trigger726/OnCall-Agent/actions/runs/36262966874) 的 V25/真实 MySQL 并发与十一项门禁全部通过，CI 状态与浏览器结果已归档。

最新值班升级：[V1.7-checkpoint-42.md](V1.7-checkpoint-42.md)：P1 未确认事故按 ON_CALL/USER/ROLE 到期执行，站内路由与无目标分开记账；前端与桌面/390px 页面通过。UTC 时钟混用已复现并修复，本地双时区各 125 项发现/110 项执行、0 失败，最新 JAR UTC HTTP 与 [Run 36260824938](https://github.com/Trigger726/OnCall-Agent/actions/runs/36260824938) 的 MySQL 8.4/十一项 CI 全部通过；截图与 CI/HTTP 精简结果已归档。

最新指标隔离：[V1.7-checkpoint-41.md](V1.7-checkpoint-41.md)：业务端口不再映射 Actuator，独立管理监听默认绑定回环；本地 119 项后端发现/105 项执行、前端 13 项和真实 JAR 双端口通过。[Run 36150079204](https://github.com/Trigger726/OnCall-Agent/actions/runs/36150079204) 的 MySQL 8.4、服务指标抓取到 Incident 与十一项 CI 全绿。

最新检索质量历史：[V1.7-checkpoint-40.md](V1.7-checkpoint-40.md)：固定评测集的最近运行可回读，按数据集版本和实际引擎隔离比较；本地 118 项后端发现/104 项执行、13 项前端、JAR 及桌面/390px 实页已通过，[Run 36147248400](https://github.com/Trigger726/OnCall-Agent/actions/runs/36147248400) 的 MySQL 8.4 与十一项 CI 全绿。

前一轮发布门禁：[V1.7-checkpoint-39.md](V1.7-checkpoint-39.md)：草稿行动项不得被逾期扫描升级、完成或外发；历史草稿通知在派发/重试处二次拦截。本地 116 项后端发现/103 项执行、JAR 已通过，[Run 36077680324](https://github.com/Trigger726/OnCall-Agent/actions/runs/36077680324) 的 MySQL 8.4 与十一项 CI 全绿。

前一轮待接手运营：[V1.7-checkpoint-38.md](V1.7-checkpoint-38.md)：已发布行动项的开放未确认计数、确认状态筛选及通知入队/派发微秒精度修复；本地 113 项后端发现/101 项执行、13 项前端、JAR 和桌面/390px 实页通过。[Run 36075906425](https://github.com/Trigger726/OnCall-Agent/actions/runs/36075906425) 的 MySQL 8.4 直接断言及十一项 CI 全绿。

前一轮负责人确认：[V1.7-checkpoint-37.md](V1.7-checkpoint-37.md)：将负责人本人确认与 webhook 2xx、行动项完成分离，增加 V23、权限/审计/幂等及双页面入口；[Run 35861441244](https://github.com/Trigger726/OnCall-Agent/actions/runs/35861441244) 十一项 CI 全绿，本地打包 JAR 的真实 HTTP/重启回归通过。2026-09-25 补验 Codex 内置浏览器中的运营分析、Incident 详情及 390px 移动布局：确认后仍开放、可单独完成，刷新保留状态；跨浏览器与真实第三方投递不在本检查点结论内。

前一轮独立进程联调：[V1.7-checkpoint-36.md](V1.7-checkpoint-36.md)：单独 Node 接收容器、503→204 故障重试与重复扫描去重已由 [Run 35859792690](https://github.com/Trigger726/OnCall-Agent/actions/runs/35859792690) 真实容器实跑验证，十一项 CI 全绿。

前一轮逾期提醒验收：[V1.7-checkpoint-35.md](V1.7-checkpoint-35.md)：默认关闭的通知 outbox、租约投递、端点 2xx 回执、重试与运营状态已完成本地全量回归；[Run 35858641814](https://github.com/Trigger726/OnCall-Agent/actions/runs/35858641814) 十项远端门禁全绿，含 MySQL 8.4 V22 入队快照断言。

历史服务指标验收：[V1.7-checkpoint-34.md](V1.7-checkpoint-34.md)：OpsPilot HTTP 401 计数器的 Prometheus 抓取、规则触发/恢复与 Alertmanager 生命周期已由 [Run 35855839535](https://github.com/Trigger726/OnCall-Agent/actions/runs/35855839535) 的真实容器联调验证，十项门禁全绿。生产阈值与管理端口的远端网络边界仍待证明。

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
