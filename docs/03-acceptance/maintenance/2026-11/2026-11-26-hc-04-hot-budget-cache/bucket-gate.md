# HC-04 WP7 分桶正确性审查

日期 2026-11-26。审查人 Codex。关联 HC-04 B1，涉及 Gateway 预占和 Backend 结算。[HC-04 设计 §4](../../../../02-development/designs/maintenance/2026-11-25-hc04-hot-budget-and-multilevel-cache-design.md#4-阶段-bp2热点预算拆子账户)

## 反例

| 项 | 结算前 | 结算后 |
|---|---:|---:|
| 预算总额 | 1.00 | 1.00 |
| 已入账实际费用 | 0.00 | 1.80 |
| 有效预占 | 1.00 | 0.00 |
| 承诺额 | 0.00 | 0.00 |
| 分桶配额上界 | 1.00 | −0.80 |

Provider 已发生的 1.80 费用必须完整入账。结算将对应预占转为 `FINALIZED`。即使所有桶的预占和配额都归零，配额之和仍无法满足 `Σ quota ≤ -0.80`。加锁、增加桶数或更换再平衡顺序都无法消除这组约束的冲突。[结算实现](../../../../../backend/src/main/java/com/aicostops/gatewaysettlement/application/GatewaySettlementService.java) · [真实 MySQL 回归用例](../../../../../backend/src/test/java/com/aicostops/gatewaysettlement/GatewaySettlementTransactionIntegrationTest.java)

## 复现

```powershell
Set-Location backend
.\mvnw.cmd -B "-Dsurefire.skip=true" `
  "-Dit.test=GatewaySettlementTransactionIntegrationTest#settledProviderCostsCanMakeTheBudgetHeadroomNegative" verify
```

MySQL 8.4 Testcontainers 下定向运行通过，1 项测试、0 失败。用例检查结算为 `SETTLED`、入账金额 1.80、预占为 `FINALIZED`、预占计数归零，以及预算可分配额度为 −0.80。

## 结论

**WP7 门禁 FAIL。** 当前方案无法同时保证配额约束与完整入账。分桶迁移和准入逻辑保持未启用。重审方案需要定义额度为负以及仍有在途预占时，如何冻结新准入并保留既有预占。
