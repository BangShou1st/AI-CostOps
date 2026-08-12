# 07. 风险清单与技术检查点

这不是形式化风险表，而是开发中真正可能导致返工、Bug 或虚假宣传的点。

| ID | 风险 | 观察信号 | 处理原则 |
|---|---|---|---|
| R1 | XLSX 导入内存压力 | 500k 接近 OOM | Streaming Reader + Bounded Batch，先测再考虑 MQ |
| R2 | Provider Schema Drift | 新列/缺列 | Fingerprint + ERROR/WARN + Parser Version |
| R3 | Duplicate False Positive | 合法重叠被当重复 | 只标 Suspicious，由人工 Review |
| R4 | Money Double Count | Total + Components 同时入账 | Adapter-specific Test |
| R5 | Budget Race | Available 被重复消费 | MySQL Conditional Update + Concurrency Test |
| R6 | 已发生成本超预算 | Posting 被 Budget 拒绝 | Cost 仍入账，显式 Over-budget |
| R7 | Period Close Race | Check 中又出现新 Posting | OPEN→CLOSING 后再检查 |
| R8 | Redis Overreach | 财务正确性依赖 Key | MySQL 是 Final Guard |
| R9 | Refresh 多 Tab 竞争 | 正常用户被误判 Replay | Previous-token Race Window |
| R10 | Permission Cache Stale | 撤权后仍能做敏感操作 | Security Version + Fresh Check |
| R11 | Provider 原始数据泄漏 | XLSX/API Key 被 Push | Sanitized Fixture + Pre-push Check |
| R12 | Git Review 形式主义 | 秒 Approve | Review Checklist + Request Changes |
| R13 | CI 太慢/Flaky | 开发者想绕过 | 分层 Check，Compose 稳定后再 Required |
| R14 | MyBatis Query 退化 | 1m Facts 查询慢 | EXPLAIN + Benchmark |
| R15 | Scope Creep 到 Gateway | V1 开始做 Proxy/Route | ADR 固定 V2 Boundary |
| R16 | Dev B 变成纯前端 | 无法解释后端 | Ledger Query / Close Blocker 等交叉任务 |
| R17 | 文档与代码漂移 | PR 静默改架构 | Design Reference + ADR Gate |
| R18 | Benchmark 变宣传 | README 写未测数字 | Release Acceptance 强制证据 |
| R19 | Provider 金额语义模糊 | 字段公式对不上 | 保留 Raw Measure + Reconciliation |
| R20 | Flyway 编号冲突 | 两 Branch 同版本号 | 开发前 Sync main，未合并 Migration 可重命名 |

## C1 — M0 后

检查：

```text
两个人能否独立 Bootstrap？
CI Check 名是否稳定？
.gitignore / Secret Policy 是否生效？
```

基础没稳，不要继续大量 Feature。

## C2 — 前两个 Provider Adapter 后

检查：

```text
ProviderAdapter Contract 是否真能容纳不同 Grain？
RawRecord → Facts 是否丢语义？
```

如果不行，此时改契约比做完五家后再改便宜。

## C3 — 第一次 100k Import 后

检查：

```text
DB-backed Worker 是否足够？
Batch Persistence 是否合理？
```

不要因为“企业项目就要 MQ”直接上 RabbitMQ。

V1.5 是否引入 MQ，靠数据和失败恢复需求决定。

## C4 — Budget 并发测试后

如果 MySQL Conditional Update 无法保证不变量，修 SQL/Transaction。

不要把财务正确性转移到 Redis Lock。

## C5 — 第一次 Ledger Posting E2E 后

检查：

```text
Source
→ Allocation
→ Budget
→ Ledger
→ Audit
```

Lineage 或 Rollback 不清楚，就暂停继续堆财务功能。

## C6 — Close Race Test 后

必须证明：

```text
CLOSING
确实阻止普通写入
```

## C7 — v1.0.0 前

统一审查：

```text
实际测试证据
Benchmark
Known Limitations
README 表述
```

技术上诚实比“企业级生产系统”口号更重要。
