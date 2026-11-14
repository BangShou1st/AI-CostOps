# 2026-11-15 维护修复验收

## 元数据

- 日期：2026-11-15
- 执行：Codex 多 agent 修复与独立复核
- 关联模块：Frontend / Nginx Edge / Gateway Provider Bridge / Observability
- 关联缺陷：2026-11-15 全项目验收发现的上传上限、依赖 HIGH、Advisor 首次配置、reasoning-only 响应问题
- PR / Issue：未创建；变更保留在工作区待评审

## 变更概述

| 缺陷 | 根因 | 修复 |
| --- | --- | --- |
| Nginx 前端拒绝大于 1 MiB 的上传 | 未配置 `client_max_body_size`，使用 Nginx 默认 1 MiB | 提升到后端请求合同 `525m`，并配置 body/proxy 超时与请求流式转发 |
| 前端完整依赖树 2 个 HIGH | `vite@8.2.1` 与 `jsdom -> undici@7.30.0` | `vite@8.3.8`；`undici@7.30.2` override；lockfile 更新 |
| Advisor 无 profile 时无法首次配置 | `ProfileForm` 同时要求 `editingProfile && profile` | 允许空 profile 表单，默认 `PROJECT` / `REQUIRED`，继续调用后端 upsert |
| reasoning-only 响应返回 502 | bridge 将 `content=null` 且无 tool calls 判为无效；响应 DTO 也不能序列化 null content | 仅对 benign empty-content 回退，保留 usage；响应允许 `content:null`；真正畸形响应仍报错 |
| 观测命令与实际拓扑不一致 | 文档使用 `compose.yaml + compose.observability.yaml`，但该拓扑没有 Gateway | 明确完整 V3 观测需叠加 `compose.v3-operational.yaml` |

## 验证环境与命令

### 静态与单元门禁

```powershell
npm run test:scripts
npm run verify:links
cd frontend
npm run lint
npm test -- --run
npm run build
npm audit --json --registry=https://registry.npmjs.org
```

结果：

- 脚本测试：56/56 PASS。
- 文档链接：264 个 Markdown 文件、545 条链接、0 broken。
- 前端：lint PASS；73 个文件、561 个用例 PASS；Vite 8.3.8 build PASS。
- 依赖审计：HIGH=0、CRITICAL=0；生产依赖审计 0；剩余 11 个 moderate 均为开发工具链。

### Gateway

```powershell
cd gateway
.\mvnw.cmd -B verify
```

- 158 个单元/架构测试与 99 个集成测试全部通过，0 失败、0 错误、0 跳过。
- `reasoning-only -> content:null + usage` 返回 200；缺 `choices` 等真正畸形响应仍为 `MALFORMED_PROVIDER_RESPONSE`。
- 真实模型复验：`marvis/deepseek-v4-1-flash-261017 (防降级)` 在 `max_completion_tokens=8` 时返回 HTTP 200、`content:null`、`finish_reason:unknown`、usage `49/8/57`；随后 `gateway_request=TRANSPORT_COMPLETED`、`gateway_usage_fact=FINAL`、`gateway_settlement=SETTLED`、ledger posting `#12`。

### Full Compose / Nginx

使用本轮构建的前端镜像和 `compose.yaml + compose.v3-operational.yaml` 独立栈验证：

- 7 个服务全部 healthy；前端容器非 root（`uid=101`）。
- `nginx -t` 成功。
- 未认证 `/api/v1/auth/me` 返回 401；登录返回 200。
- 通过 Nginx 上传 2 MiB provider import：HTTP **201**，生成 `evidenceId=1`、`importBatchId=1`；不再出现 413。

### Advisor 浏览器

在全新数据库、无 Advisor profile 的组织中用 `bsk` 登录 `/advisor`：

- 页面显示“暂无执行配置，请创建首个配置。”
- 点击“更新执行配置”后展示执行模型 ID、项目 ID、财务范围类型/ID、预算模式的完整表单。
- 单测验证首次提交精确调用 `PUT /api/v1/ai-advisor/profile` payload。

## 关键证据

- 上传与 Nginx：`.local-dev/acceptance/fixes/nginx-upload.md`
- 依赖修复：`.local-dev/acceptance/fixes/dependencies.md`、`audit-before.json`、`audit-after.json`、`audit-after-prod.json`
- Advisor：`.local-dev/acceptance/fixes/advisor.md`、`advisor-first-profile-form.png`
- Gateway：`.local-dev/acceptance/fixes/gateway-reasoning.md`、`gateway-reasoning-verify.log`
- 真实模型：`.local-dev/acceptance/fixes/real-model-reasoning-only.json`、`real-model-reasoning-only.md`
- 独立复核：`.local-dev/acceptance/fixes/independent-verification.md`

## 结论与影响边界

- 修复判定：**PASS**。
- 两个 P1（上传上限、依赖 HIGH）与已确认 P2（Advisor 首次配置）均已关闭。
- reasoning-only 回退路径在 bridge 异常前无法保留 Provider `finish_reason`，公开响应显示 `unknown`；usage、预算、结算与账本事实不受影响。
- 11 个 moderate 开发工具链依赖保留，不构成本轮 HIGH/CRITICAL 发布阻断，但应继续跟踪。
- 未运行 GitHub Actions、未提交/创建 PR；最终合并门禁以远端 CI/Security 结果为准。
