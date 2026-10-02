# SLO 规则导出与受控发布

运营分析页面的当前活跃 ADMIN/OPS_MANAGER 可点击“导出告警规则”。页面捕获服务目标的 id/version/目标百分比/周期，导出 API 拒绝旧版本或停用目标，浏览器验证返回元数据和文件 SHA256 后才允许下载。只读角色没有导出入口。关闭窗口或离开页面会中止读取。

GET `/api/v1/slo/objectives/{id}/versions/{version}/prometheus-rules` 返回规则文件、摘要、策略版本及规则数。版本来自刚刚核对的目标，不在服务端自动换成最新版本；接口不提供旧配置的历史重建。导出使用同一事务快照，并且不调用外部指标平台或自动发布。

## 发布流程

1. 查询目标，核对服务、好事件/总事件表达式、周期和版本，下载文件。
2. 对实际下载文件运行 `promtool check rules <文件>`，并校验内容摘要。文件使用 YAML 兼容的 JSON 编码，Prometheus 原生解析器已测试。
3. 核对表达式对应相同事件口径，各返回一个标量或单一时间序列。无序聚合多个服务或混用成功请求数/独立用户数会制造错误分母；原生语法校验不能证明业务口径正确。生产模板应基于真实 counter 的 increase 等统计，不能直接使用验收 fixture 的 gauge。
4. 在测试 Prometheus 中验证实际数据、counter reset、窗口覆盖、采集故障及预计告警。记录规则最长读取3d历史（1/2天目标相应缩短），保留期和数据覆盖需满足实际窗口。
5. 将文件加入 Prometheus 的 rule_files，配置 Alertmanager，再由运维执行配置校验和重载。Alertmanager receiver 使用专用 OpsPilot webhook 密钥和 send_resolved，匹配服务 resource_code。替换目标配置时移除旧版本规则，避免两个版本同时生成独立告警；已经导出的文件不会随数据库更新自动变化。
6. 验证 firing、重复投递、resolved 的 OpsPilot 告警与时间线。告警恢复不自动关闭 Incident，值班人仍需完成事故处置。

## 策略

阈值 = 目标周期小时数 × 预算消耗比例 ÷ 长窗口小时数，见 [Google SRE 原始说明](https://sre.google/workbook/alerting-on-slos/)。默认30天为1h/5m、14.4x；6h/30m、6x；3d/6h、1x。28天为13.44x、5.6x、0.933333…x。目标1/2天的票据使用1d/2h、2d/4h，保持长窗口不超过目标周期、短窗口为长窗口的1/12。

同组顺序执行好事件/总事件记录、有限且有效的燃烧率记录，再计算快档/慢档/票据优先级。三档均要求两个窗口达到阈值，快档抑制慢档，票据排除两个PAGE档。规则保留服务、目标版本、窗口和严重等级标签；标签中不包含查询结果或凭证。

每30秒评估，记录超过60秒视为不可用；空结果、多系列、非正分母、负好事件、好事件大于总事件以及NaN/Inf均不产生燃烧率。每个窗口有独立数据不可用告警，避免把缺数据当健康。60秒新鲜度针对记录规则评估时间，不是对任意源模板数据覆盖的证明；生产采集健康仍需单独监控。

低流量服务是否应限制样本、合并渠道或采用其他政策需结合业务制定；本实现保留原始计数判定，不擅自压制低流量告警。协议验收使用合成窗口计数，原生单元场景另外验证increase/counter reset；两者均不能证明生产流量质量或完整长期历史。

## 可复跑验收

设置 JDK 和原生工具路径 OPSPILOT_PROMTOOL、OPSPILOT_PROMETHEUS、OPSPILOT_ALERTMANAGER（或将工具放入PATH），打包当前JAR后执行：

```bash
node scripts/verify-slo-prometheus-rules.cjs
node scripts/verify-slo-alerting-pipeline.cjs
```

脚本检查9918/9922/9094/9095空闲并只清理自己创建的进程，使用唯一H2内存库；真实Prometheus、Alertmanager和一个本机计数/投递转发记录fixture参与联调。20组原生规则场景和重复firing/恢复幂等另有独立CI必过作业，最终容器烟测依赖该作业。真实MySQL导出读取加入既有MySQL套件。新旧桌面/手机图及阶段边界见[检查点58](acceptance/V1.7-checkpoint-58.md)。
