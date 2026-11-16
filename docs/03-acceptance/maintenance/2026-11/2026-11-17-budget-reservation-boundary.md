# Gateway 预算保守预占边界登记

## 元数据

- **日期**：2026-11-17
- **责任人 / AI**：Codex
- **关联模块**：`gateway/budget`、`gatewaysettlement`
- **关联缺陷 / 决策**：1,048,576 token 输入保守预占、请求成功后不缩减预占、5 秒结算轮询

## 变更概述

本轮不改变冻结的 M12 预占算法，也不在请求成功后缩减 `budget_reservation.reserved_amount`。
保留以下已接受边界：

1. 输入侧按固定的 `1_048_576` token 计算保守上界，不对 prompt 做字符/token 估算。
2. 请求成功后预占仍保留到异步 Settlement 核销；结算 Worker 默认每 5 秒轮询。
3. 小额预算在低并发下可能因保守预占暂时出现可用额度偏低；这是避免预算击穿和
   避免 `missing usage = zero cost` 的保守代价。

新增 `gateway_budget_reservation_amount`（按 currency 的低基数分布摘要）用于观察
预占金额分布；MySQL `budget_reservation` 仍是授权与金额的唯一事实来源。

## 验证环境与执行命令

```powershell
cd gateway
.\mvnw.cmd -B "-Dtest=ReservationAmountCalculatorTest,GatewayMetricsTest" test
```

## 关键断言与证据

- `ReservationAmountCalculator.INPUT_RESERVATION_TOKENS` 仍为 `1_048_576L`。
- `GatewayMetricsTest.reservationAmountSummaryUsesOnlyCurrencyLabel` 验证新指标只带
  受控 currency 标签，并记录保守预占金额。
- 现有预占并发/恢复/Settlement 定向回归保持通过。

## 结论与影响边界

**结论：接受为 MAINTENANCE 运维边界，不视为本轮缺陷修复。**

若未来要按模型上下文窗口收敛或成功后缩额，必须先修改冻结设计、模型目录契约与
Flyway/API/前端，并重新验证预算不超扣；本轮不纳入。
