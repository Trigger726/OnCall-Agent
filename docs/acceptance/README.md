# OpsPilot 验收记录

本目录用于保存 OpsPilot 的阶段性验收报告。项目按“完成一组改动、形成一组证据、写入一份报告”的节奏持续迭代，报告中的结论只覆盖已经获得直接证据的范围。

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

## 状态约定

- `通过`：该检查点的功能、测试、运行和文档证据均已闭环。
- `部分通过`：实现和部分验证已经完成，但仍有明确待验项。
- `不通过`：发现阻断交付的问题，需要修复后重新验收。
