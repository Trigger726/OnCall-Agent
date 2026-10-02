# Collector 队列恢复与历史对照

本说明针对固定 `otelcol-contrib:0.158.0` 的开发/验收 Compose 链路，不是生产“零丢失”承诺。首失败和修复验收见[阶段报告](acceptance/V1.7-checkpoint-61.md)。

## 正常配置与边界

`deploy/otel-collector.yml` 在 `otlp/tempo.sending_queue` 引用已启用的 `file_storage`。目录 `/var/lib/otelcol/storage` 挂独立 `otel-collector-data` 命名卷，容器重建不依赖可写层。镜像用户10001:10001；一次性 init 只将卷根目录设为该所有者和0750，Collector 本身不改 root。

- 队列2048的默认单位是导出请求/批次，不是2048个Span或固定内存大小。默认消费者仍为10。
- 单消费者和宿主机回环18888映射只在故障测试中启用。Collector指标监听容器内8888，不在普通Compose发布该宿主机端口；可选Prometheus监控从容器网络抓取。
- `fsync:true` 每次写同步落盘，有性能成本，尚无生产吞吐结论。
- `max_size:268435456` 是每个 bbolt 文件的增长上限，不是目录总大小、卷配额或保留期；已分配空间中的部分写入仍可能成功。
- 重试窗口仍为60秒。磁盘满、队满、超过重试窗口、宿主机/卷丢失、未入队的 SDK/Collector batch 缓冲都可能丢数据。
- HTTP OTLP接收成功和接收计数不等于每条Span已持久入队，尤其入口仍有batch处理器。实验先等待导出及积压，再以完整原Trace恢复为最终证据。
- 一个 Collector 实例使用自己的卷/目录，不让两个进程同时写同一 bbolt 文件，不承诺 Exactly Once 或无重复。
- 未启用 `recreate:true`，避免坏库被静默换成空库。发现损坏应先停止拥有该卷的 Collector、保留卷和日志，再调查受控副本，不能删除卷“解决”。普通 `docker compose down` 保留卷，切勿对日常 Demo 执行 `down --volumes`。

## 可复跑故障实验

在带 Docker Linux daemon、Compose、Bash、curl、jq、openssl、xxd 的环境，从仓库根目录运行：

```bash
bash scripts/verify-trace-assertions.sh
bash scripts/verify-collector-metrics.sh
bash scripts/verify-collector-saturation-assertions.sh
bash scripts/verify-tracing-pipeline.sh
```

脚本生成独立 Compose 项目名，只清理该次拥有的临时容器/卷，保留 `target/tracing-it/`。9900、9920、3200、3000、4318、13133、18888、9090和9910端口须空闲，不能与日常 Demo 并跑。CI同样执行以上步骤、原生promtool两条规则/12告警断言，并上传原始工件。

实验保留正常链路、Tempo 单独停机和单消费者SIGKILL/不同容器重建，再补默认十消费者及小容量饱和场景，当前阶段证据见[62报告](acceptance/V1.7-checkpoint-62.md)。重建前探针用于观察在途请求，不替代真实调查图；所有探针和原Trace ID/runId、六工具及两条Provider→CLIENT→SERVER须恢复，父子关系正确、同Trace且Span ID唯一。TraceQL/Grafana读回不重提调查。

Tempo搜索可能省略前导零；测试仅对合法非零hex左补零并小写规范化后精确比较128-bit ID，绝不转浮点数字或做子串匹配。当前实测强制前导0，历史首次ID误判与真正的内存队列丢失分别保留。

主要工件：`result.json`、`outage-result.json`、`restart-result.json`、原Trace/TraceQL/Grafana响应、队列指标快照、SIGKILL状态、重建前后挂载、实际用户/权限、完整Compose日志。总接收量只是前置条件，不替代指定Trace验证。

## 可选饱和监控

```bash
export OTEL_TRACING_ENABLED=true
docker compose --project-name opspilot-trace-monitoring-demo-62 \
  -f docker-compose.yml -f deploy/docker-compose.tracing-monitoring.yml \
  --profile tracing up --build -d
```

要求固定端口空闲，与日常/历史Demo串行；停止只操作该独立项目，保留卷。此覆盖保留原OpsPilot指标抓取，加Collector抓取和两条规则；普通`deploy/prometheus.yml`不改。只声明可选开发/验收监控，不外推生产SLA。

- `CollectorTraceQueueHigh`：每个实例的trace出口队列超过90%持续30秒，容量须为正；队列下降后恢复。
- `CollectorTraceEnqueueRejected`：最近计数增量，或首次观测到正值拒绝序列。拒绝counter按实际固定版本标签筛选，不虚构queue gauge才有的data_type。无后续拒绝时5分钟历史窗口过期；队列恢复后短期继续firing并不等于当前仍满。
- 缺数据不伪造健康，也不生成这两类事故。首次正值只说明已观测到拒绝，不证明每条拒绝的精确时刻；抓取间隔/重启/丢样会影响计数，本规则不替代持久审计账本。
- 当前只到Prometheus原生告警，未配置Alertmanager路由或OpsPilot Incident/人工通知；不得把反馈规则当成外部渠道已送达。

故障测试仅用queue4/消费者1/每批1Span，生产配置仍2048/默认10/批触发512。OTLP200及partialSuccess拒绝0、业务COMPLETED/健康UP仍可能与入队拒绝/已知Trace缺失同时发生；这个实验用于直接展示边界与检测反馈，不修改SDK业务状态或承诺无丢失。判定缺失须先确认Tempo恢复、队列和在途均清空、恢复后独立已知ID正对照经同一入口实际送达以及缺失计数重复稳定。后台Span可能提前占满小队列，原探针不保证有一条幸存；不能仅以12条都404当作下游已恢复。

## 保留旧内存 Demo

旧配置保存在 `integration/tracing/baseline/otel-collector-memory.yml`，历史演示另用项目名和覆盖文件：

```bash
export OTEL_TRACING_ENABLED=true
docker compose --project-name opspilot-trace-memory-demo-61 \
  -f docker-compose.yml -f integration/tracing/baseline/docker-compose.memory.yml \
  --profile tracing up --build -d
```

它使用旧内存出口，不使用file_storage；新Compose可能准备独立数据卷，但旧配置不会写该卷。固定端口要求新旧Demo串行；停止时只操作该项目且保留卷。当前CI不加载旧覆盖，没有绕过恢复断言的开关。精确复现首次失败可用保留的 `02be17f` 提交或对应CI工件，不覆盖当前工作树。

依据：[官方韧性说明](https://opentelemetry.io/docs/collector/resiliency/)、[v0.158.0 file_storage](https://raw.githubusercontent.com/open-telemetry/opentelemetry-collector-contrib/v0.158.0/extension/storage/filestorage/README.md)及[v0.158.0 exporterhelper](https://raw.githubusercontent.com/open-telemetry/opentelemetry-collector/v0.158.0/exporter/exporterhelper/README.md)。后续仍需生产容量/采样、队满/磁盘满与卷损坏演练，以及真实生产Prometheus/Loki服务端联调。
