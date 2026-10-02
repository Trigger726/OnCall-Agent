# Collector 原生告警受控进入 Incident

这是显式开启的本地 Compose 演示，不改变普通离线启动，也不替代[62的监控-only对照](COLLECTOR-QUEUE-RECOVERY.md)。阶段证据见[63报告](acceptance/V1.7-checkpoint-63.md)，以报告实际验收状态为准。

## 启用与资源登记

需要 Docker Linux daemon、Compose，固定端口9900/9920/9090/9093/3200/4318/13133等须空闲。历史和新Demo串行运行，项目名不同；不要覆盖历史工作树或删除历史卷。

先准备一个私有目录中的凭证文件，内容与环境变量 `ALERTMANAGER_WEBHOOK_SECRET` 完全一致，将文件绝对路径设为 `COLLECTOR_ALERTMANAGER_SECRET_FILE`。不使用仓库默认值，不提交密钥，不把带展开环境变量的Compose配置输出保存到日志。文件需让Alertmanager容器用户读取；Linux可用0700私有父目录隔离宿主机访问，Windows须检查ACL。挂载为只读，注意文件与环境变量双份更新要同步。本地容器网络使用HTTP，不是生产TLS或密钥管理方案。

```bash
export OTEL_TRACING_ENABLED=true
# 上述两个凭证变量必须由操作者预先配置。
docker compose --project-name opspilot-collector-alerting-demo-63 \
  -f docker-compose.yml -f deploy/docker-compose.collector-alerting.yml \
  --profile tracing --profile collector-alerting up --build -d
```

Prometheus仍抓OpsPilot指标，另抓Collector内部8888并加载原生两条规则，将专属实例标记为 `resource_code=OBS-OTEL-COLLECTOR`。Alertmanager只转发两种Collector告警且job/resource_code同时匹配的事件；其它告警进入没有外部集成的unmatched路由，不静默映射成APP-PORTAL/APP-AUTH。按alertname/resource_code/instance/exporter分组，恢复通知开启。

CMDB登记是独立、显式的操作者步骤。仅在确认此项目是本地Demo且Collector实际存在后，审阅 `deploy/register-demo-collector-resource.sql`：它登记MIDDLEWARE/DEVELOPMENT、无负责人；已有同code不覆写，重复串行执行不新增。它不在Flyway或离线启动中自动执行，不是生产自动发现、健康探测或人工责任人分派。在生产中须使用真实库存/环境/负责人，同时审阅Prometheus标记与Alertmanager路由；不能直接复制Demo库存。

可在确认本项目数据库后执行：

```bash
docker compose --project-name opspilot-collector-alerting-demo-63 \
  -f docker-compose.yml -f deploy/docker-compose.collector-alerting.yml \
  --profile tracing --profile collector-alerting \
  exec -T -e MYSQL_PWD=opspilot-local mysql \
  mysql -uopspilot opspilot < deploy/register-demo-collector-resource.sql
```

此数据库口令仅对应仓库本地Compose，不用于生产。停止仍用同项目名/覆盖/profile执行 `down`，不带 `--volumes`，保留日常和历史Demo数据。

## 可观察行为与边界

- 凭证缺失/错误：401，不进入业务接入或拒绝账本。
- 凭证正确但资源未登记：批次HTTP成功不代表每条已入库；具体事件被RESOURCE_NOT_FOUND拒绝并留下可恢复记录。Alertmanager不会理解业务批次内REJECTED。
- 修复库存后：下一次原生重复通知可建立Alert/Incident并将匹配拒绝标记SUCCEEDED；默认repeat_interval为1小时，不承诺登记立即生效。另有现存受权限控制的拒绝重放功能，本轮不修改其策略、不启用自动重放代替真实重复通知。
- 队列超过90%持续30秒触发P2；观测到入队拒绝触发P1。拒绝规则保留5分钟历史窗口，队列清空后短期firing不表示仍队满；无后续拒绝且历史窗口过期才恢复。
- 同一生命周期重复通知不新建事故，也不修改业务计数/版本/时间字段。恢复须保持相同Alert/Incident及外部生命周期键，每条告警写一次恢复时间线。
- Alert RESOLVED不等于Incident CLOSED/RESOLVED。无人接单的事故仍OPEN；本Demo不自动赋予负责人、确认或人工回执，不把通知尝试计数当真实人工送达。
- 普通队列2048/默认10消费者/60秒重试不变。队满、磁盘/坏卷、超过重试预算、入口batch缓冲仍可能丢Trace；告警检测与Incident接入不修复已丢数据，也不代表生产容量或HA保证。

## 自动化故障实验

```bash
bash scripts/verify-collector-alerting-routes.sh
bash scripts/verify-collector-alertmanager-metrics.sh
bash scripts/verify-collector-alerting-pipeline.sh
```

本轮真实Linux实验使用独立项目/新MySQL，暂停Tempo造成真实出口阻塞；测试队列16/消费者1/每批32Span，24个真实OTLP故障fixture请求各32Span，超出16请求容量。此前queue4/batch1在恢复后仍因原生投递自身Trace批次产生拒绝，第二次失败明确保留，不能说已恢复；62极小队列反例仍原样保留。新实验保持应用Trace启用，不关闭告警链路，额外要求队列清空到最终resolved期间拒绝counter不再增长。先验证鉴权与未登记拒绝，显式登记两次，再验证原生投递、重复通知无业务写、恢复后的同ID及两条时间线，保留真实30秒hold和5分钟窗口。只加快测试抓取/通知cadence，不重启Collector或Prometheus清counter、不提交伪造resolved事件。

工件保留原始告警快照、拒绝、CMDB、Incident、时间线、Prometheus状态、Collector/Alertmanager指标及完整Compose日志；随机真实凭证不在证据目录。测试退出仅清理自己拥有的隔离项目/卷，不能对日常Demo运行这个清理操作。

固定v0.34.1通知指标默认只有integration，启用可选功能才加receiver_name。当前测试只有一个webhook集成，故按integration统计尝试，额外要求失败counter存在且为0，并用真实DB证据证明业务接收及重复幂等；多receiver生产配置不能由此归因到某个接收者。依据：[官方路由与文件凭证](https://prometheus.io/docs/alerting/latest/configuration/)、[固定版本指标源码](https://raw.githubusercontent.com/prometheus/alertmanager/v0.34.1/notify/metrics.go)。
