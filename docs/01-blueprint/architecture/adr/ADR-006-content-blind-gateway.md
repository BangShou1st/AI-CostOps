# ADR-006 — V2 Gateway 默认不保存 Prompt / Response 正文

**状态：** Accepted as V2 security principle

## 背景

成本治理需要 Usage/identity/cost，但通常不需要完整业务内容；保存内容会扩大隐私与安全风险。

## 决策

默认记录：

```text
request id
identity/project
provider/model
token metrics
latency/status
cost
```

默认不记录：

```text
prompt body
response body
```

## 影响

- 降低敏感数据面；
- 成本系统与 observability/content platform 分离；
- 某些 debug 场景无法直接看正文。

如未来确需采样，必须独立 consent/retention/redaction policy。
