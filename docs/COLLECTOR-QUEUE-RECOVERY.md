# Collector 队列恢复与历史对照

本说明针对固定 `otelcol-contrib:0.158.0` 的开发/验收 Compose 链路，不是生产“零丢失”承诺。首失败和修复验收见[阶段报告](acceptance/V1.7-checkpoint-61.md)。

## 正常配置与边界

`deploy/otel-collector.yml` 在 `otlp/tempo.sending_queue` 引用已启用的 `file_storage`。目录 `/var/lib/otelcol/storage` 挂独立 `otel-collector-data` 命名卷，容器重建不依赖可写层。镜像用户10001:10001；一次性 init 只将卷根目录设为该所有者和0750，Collector 本身不改 root。

- 队列2048的默认单位是导出请求/批次，不是2048个Span或固定内存大小。默认消费者仍为10。
- 单消费者和回环18888指标接口只在故障测试覆盖中启用，不进入日常 Demo。
- `fsync:true` 每次写同步落盘，有性能成本，尚无生产吞吐结论。
- `max_size:268435456` 是每个 bbolt 文件的增长上限，不是目录总大小、卷配额或保留期；已分配空间中的部分写入仍可能成功。
- 重试窗口仍为60秒。磁盘满、队满、超过重试窗口、宿主机/卷丢失、未入队的 SDK/Collector batch 缓冲都可能丢数据。
- HTTP OTLP接收成功和接收计数不等于每条Span已持久入队，尤其入口仍有batch处理器。实验先等待导出及积压，再以完整原Trace恢复为最终证据。
- 一个 Collector 实例使用自己的卷/目录，不让两个进程同时写同一 bbolt 文件，不承诺 Exactly Once 或无重复。
- 未启用 `recreate:true`，避免坏库被静默换成空库。发现损坏应先停止拥有该卷的 Collector、保留卷和日志，再调查受控副本，不能删除卷“解决”。普通 `docker compose down` 保留卷，切勿对日常 Demo 执行 `down --volumes`。

## 可复跑故障实验

在带 Docker Linux daemon、Compose、Bash、curl、jq、openssl 的环境，从仓库根目录运行：

```bash
bash scripts/verify-trace-assertions.sh
bash scripts/verify-tracing-pipeline.sh
```

脚本生成独立 Compose 项目名，只清理该次拥有的临时容器/卷，保留 `target/tracing-it/`。9900、9920、3200、3000、4318、13133、18888和9910端口须空闲，不能与日常 Demo 并跑。CI同样执行两步并上传原始工件。

三段依次验证正常链路、Tempo 单独停机恢复、Tempo 停机时 Collector SIGKILL/不同容器重建。第三段以另一个合成Trace占用测试消费者，再生成真实调查；blocker不是验收成功对象。只认预先注入的原Trace ID和原runId，六工具与两条Provider→CLIENT→SERVER须齐全、父子关系正确、全图同一Trace且Span ID无重复。最后通过TraceQL与Grafana再读同一图，不重提调查。

主要工件：`result.json`、`outage-result.json`、`restart-result.json`、原Trace/TraceQL/Grafana响应、队列指标快照、SIGKILL状态、重建前后挂载、实际用户/权限、完整Compose日志。总接收量只是前置条件，不替代指定Trace验证。

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
