# 2026-11-13 全仓库文档维护

| 项目 | 内容 |
|---|---|
| 日期 | 2026-11-13 |
| 责任人 | AI（Codex） |
| 关联模块 | 根级状态文档、01-blueprint、02-development、03-acceptance、04-operations、scripts、deploy |
| 缺陷 / PR 编号 | 无对应缺陷；PR 编号以 GitHub 合并记录为准 |
| 代码基线 | `dev = fca2d97`，`main = dcefc62`，`v3.1.1 -> bef5877` |
| 变更性质 | 纯文档与文档校验工具维护；无 Java / TypeScript / SQL / 配置 / 财务语义变更 |
| 总体判定 | **PASS**：版本、技术事实、索引、路径与链接全部对齐；`verify:links` 0 broken links |

## 1. 变更概述与根因

本次维护针对长期维护期最容易累积的“文档漂移”，不是功能性改造。根因分为四类：

1. **当前状态口径漂移**：`AGENTS.md`、`03-acceptance/README.md`、产品路线图与生产部署说明仍停留在
   `v3.0.0`，而 `main` 上的稳定标签已是 `v3.1.1`，且 dev 上已有 v3.1.2 所需的运行期修复。
2. **技术事实过期**：README 多处写 `Redis 7`，实际 `compose.yaml` 与 Testcontainers 均使用
   `redis:8.8.1-alpine`；代码规模、测试文件数、前端套件数与 CI 作业列表也未随维护更新。
3. **路径迁移未同步**：`v2-detailed-design/`、`02-development/operations/`、`m18-openapi.yaml`
   等旧路径仍被现行文档引用；历史验收记录保留旧路径，但缺少统一映射。
4. **索引不完整**：API、V1 设计、implementation 目录存在未登记文档；`milestones/` 缺少独立索引。

对应处理：

- 根级状态统一到 `STABLE_RELEASE = v3.1.2`，发布表、分支策略与 `PROJECT_CONTEXT.md` 同步。
- README 的 Redis 版本、技术栈版本、代码规模、测试套件数与 CI 说明按当前仓库事实重算。
- 现行文档改用新路径；历史/归档文档不改写结论，改由 `docs/README.md` 的“历史路径映射”解释。
- 补齐 [`docs/02-development/designs/README.md`](../../../02-development/designs/README.md)、
  [`docs/03-acceptance/milestones/README.md`](../../milestones/README.md) 与 API / V1 / implementation 索引。
- CHANGELOG 补齐 `3.1.0` 与 `3.1.2` 发布段及比较链接；`3.0.0` 的“当前 Stable”改为历史表述。

## 2. 验证环境与执行命令

```text
Windows 11 + PowerShell 7
Node v24.14.0
仓库：E:\project\AI-CostOps
```

```powershell
# 全仓 Markdown + 本地图片链接
npm run verify:links

# 脚本测试（链接校验器所在脚本目录）
npm run test:scripts

# 差异空白与冲突标记检查
git diff --check
```

## 3. 关键断言

```text
verify:links  -> Found 261 Markdown files / Checked 537 links
verify:links  -> SUCCESS: 0 broken links
test:scripts  -> PASS
git diff --check -> PASS（无空白错误）
```

关键数字核对：

| 项目 | 维护前 | 维护后事实 |
|---|---|---|
| 稳定版本 | README/AGENTS/路线图混用 `v3.0.0` / `v3.1.1` | 统一 `v3.1.2` |
| Redis | README 多处 `Redis 7` | `Redis 8.8.1-alpine` |
| Backend 主源 | 637 文件 / 47,945 行 | 640 文件 / 48,754 行 |
| Backend 测试 | 308 文件 / 61,525 行 | 319 文件 / 63,320 行 |
| Gateway 主源 | 97 文件 / 9,227 行 | 98 文件 / 9,761 行 |
| Gateway 测试 | 61 文件 / 9,229 行 | 67 文件 / 10,672 行 |
| Frontend 主源 | 246 文件 / 24,082 行 | 177 文件 / 14,574 行 |
| Frontend 测试 | 72 套件 | 73 套件 / 9,970 行 |
| IntegrationTest 类 | 文档写 199 | 201 |

## 4. 结论与影响边界

**PASS。** 本次维护只改文档、CHANGELOG、文档索引与链接校验脚本；不修改业务代码、
Flyway、OpenAPI 语义、运行配置或财务不变量。`v3.1.2` 作为稳定版本记录在
`PROJECT_CONTEXT.md`，其代码内容来自本次维护前 `main` 上已合并的运行期修复。

已知边界：

- 历史里程碑与 `docs/superpowers/` 归档保留原始路径和当时结论，不按当前状态改写；
  旧路径统一由 [`docs/README.md`](../../../README.md) 的历史路径映射解释。
- 本次未重跑完整 Backend / Gateway 集成测试与浏览器 E2E；文档维护不改变运行时，
  但仍以 GitHub Actions 的最终结果作为合并门禁。
