# ADR-005 — V1 先做 Post-billing Ledger，Gateway 放到 V2

**状态：** Accepted

## 背景

跨全部 Coding SaaS 做实时控制并不可行；只有企业能控制请求链路的 API traffic 适合 Gateway。

## 决策

V1：

```text
Evidence/Statement
→ Ledger
→ Reconciliation
→ Close
```

V2：

```text
Gateway
→ Redis/Lua Reservation
→ Usage
→ Settlement
→ same MySQL Ledger
```

## 影响

- V1 能单独成为完整业务系统；
- 不为了“技术炫”提前承担 streaming/high-availability；
- V2 复用 V1 Ledger，不推倒重来。

## 不做的内容

V2 也不承诺实时控制所有 Cursor/Coding Plan/个人 SaaS。
