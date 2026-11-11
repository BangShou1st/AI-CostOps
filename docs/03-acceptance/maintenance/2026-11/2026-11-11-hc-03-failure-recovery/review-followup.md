# HC-03 PR #181 合并前审查修正（2026-11-12）

## 1. 元数据

| 项 | 值 |
|---|---|
| 关联 PR / Issue | [#181](https://github.com/BangShou1st/AI-CostOps/pull/181) / [#180](https://github.com/BangShou1st/AI-CostOps/issues/180) |
| 审查意见 | [PR #181 issuecomment-6800611157](https://github.com/BangShou1st/AI-CostOps/pull/181#issuecomment-6800611157) |
| 审查时 PR head | `109179e` |
| 故障演练证据基线 | `78ee975`（9 个场景 ×3 次，财务违例 0；本轮**不重跑**全部 27 轮） |
| 本轮范围 | 仅 A：恢复真实 CI/Security 验收；B：HC-03 harness 资源所有权加固；文档与 R-3 表述修正 |
| 未改动 | 业务状态机、Flyway/DDL、预算、结算、账本、API 契约 |
| 结论 | 待 CI/Security 实际全绿后填写（不以本地推断代替） |

## 2. 任务 A：Trivy / CI 恢复

### 2.1 定位

- Trivy 失败步骤：`Trivy backend image scan (vuln)`；扫描对象是 security workflow 现场构建的
  `ai-costops-backend:security`。
- 受影响层：`backend/Dockerfile` 的 runtime 阶段 `FROM eclipse-temurin:21-jre-noble`；
  扫描结果 `apt 2.8.3` / `libapt-pkg6.0t64 2.8.3`。
- Ubuntu 24.04 修复版本为 `2.8.4ubuntu0.1`（CVE-2026-109518，HIGH）。仓库没有钉住 apt 版本，
  Dockerfile 本来就有 `apt-get update && apt-get upgrade --yes`，所以问题在**该次 CI 构建没有取到修复包**，
  而不是代码把 apt 固定在了 2.8.3。
- 同一时间窗口的 `browser-e2e` 日志出现
  `Temporary failure resolving 'azure.archive.ubuntu.com' / 'archive.ubuntu.com' / 'security.ubuntu.com'`，
  说明 GitHub 托管运行器的 Ubuntu 镜像 DNS/索引在该时段不可用；`apt-get upgrade` 只能使用
  基础镜像自带/失败索引，因此镜像仍为 2.8.3。

复现与确认（本机可用网络）：

```powershell
# 基础镜像实际 sources（非注释）
docker run --rm eclipse-temurin:21-jre-noble bash -c \
  "grep -v '^#' /etc/apt/sources.list.d/ubuntu.sources | grep -v '^$'; \
   apt-get update -qq; apt-cache policy apt | head -6"
# → archive.ubuntu.com / security.ubuntu.com；Candidate: 2.8.4ubuntu0.1

# 使用与 backend/Dockerfile 完全相同的 runtime 阶段（仅把 COPY jar 换成 dummy）重建
docker build --no-cache -f .local-dev/probes/Dockerfile.runtime -t hc03-runtime-probe .local-dev/probes
docker run --rm --entrypoint bash hc03-runtime-probe -c \
  "dpkg -s apt | grep Version; dpkg -s libapt-pkg6.0t64 | grep Version"
# → Version: 2.8.4ubuntu0.1（两者）
```

本机 Trivy 0.73.0 尝试扫描该镜像时，Java DB 下载在网络上 `unexpected EOF` 失败（不是漏洞结果），
因此最终 gate 只能由原始 CI 的 Trivy 给出；没有把本地推断当作 GREEN。

### 2.2 修复（不降低任何门槛）

1. `backend/Dockerfile` / `gateway/Dockerfile` 的 runtime 阶段增加**构建期版本下限断言**：
   ```dockerfile
   && echo "runtime apt=$(dpkg-query -W -f='${Version}' apt) libapt-pkg6.0t64=$(dpkg-query -W -f='${Version}' libapt-pkg6.0t64)" \
   && dpkg --compare-versions "$(dpkg-query -W -f='${Version}' apt)" ge "2.8.4ubuntu0.1" \
   ```
   若修复包取不到，镜像构建直接失败并打印实际版本——**绝不**生成一个仍含该 CVE 的镜像再交给
   扫描器“碰运气通过”。
2. `.github/workflows/security.yml` 三个镜像改为 `docker build --pull --no-cache`：
   强制刷新 base image 与 apt 索引层，排除构建层缓存导致的陈旧包。
3. **未修改** `.trivyignore`，未降低 severity，未使用 `--ignore-unfixed`/`--exit-code 0`，未跳过扫描。
4. `browser-e2e` 未做任何代码改动；其失败为 `Install Chromium system dependencies` 的 DNS 解析失败，
   保留原始日志，在网络恢复后直接复跑。

### 2.3 验证结果

**本机（可用网络）**：

| 项 | 结果 |
|---|---|
| 与 Dockerfile 完全相同的 runtime 阶段重建 | apt `2.8.4ubuntu0.1`、libapt-pkg6.0t64 `2.8.4ubuntu0.1` |
| 本机 Trivy 0.73.0 | 未完成（Java DB 下载 `unexpected EOF`，本机网络问题；不作为漏洞结论） |

**GitHub Actions（`5482ac4`；Canonical DNS 恢复后重跑）**：

| 检查 | 结果 | 证据 |
|---|---|---|
| Security `trivy` | **PASS**（3m15s） | run 43058883397 / job 130677365846；镜像构建日志 `runtime apt=2.8.4ubuntu0.1 libapt-pkg6.0t64=2.8.4ubuntu0.1`（backend 与 gateway） |
| CodeQL (java/js) | PASS | 同 run |
| CI `docker-build` | PASS（2m1s） | run 43058883406 / job 130683837109 |
| CI `browser-e2e` | PASS（5m53s） | run 43058883406 / job 130683837084 |
| CI `gateway-unit` / `gateway-integration` / 后端 / scripts / frontend | PASS | 同 run；`gateway-integration` 含新增 outage 测试 |
| CI `ci-status` | PASS | run 43058883406 / job 130687273845 |

> 期间 runner 对 `archive.ubuntu.com` / `security.ubuntu.com` 的解析间歇性失败：同一提交在 16:30Z
> 的三次重跑均因 DNS 失败被构建期断言拦下；DNS 恢复后，**原始镜像、原始扫描、原始 exit-code 1**
> 直接通过。没有修改 `.trivyignore`、没有降低 severity、没有跳过扫描。
>
> 结论：任务 A 的“真实 CI/Security 验收”已恢复为 GREEN（apt 2.8.4ubuntu0.1，Trivy HIGH/CRITICAL=0）。
## 3. 任务 B：资源所有权加固

### 3.1 设计

| 资源 | 身份证明 | 破坏性操作前的校验 | 不匹配时 |
|---|---|---|---|
| 容器 | label `aicostops.hc03.owner` / `.run` / `.resource` | `docker inspect` label owner 必须等于当前 owner | 拒绝；无 label 或他人 owner 输出人工 `docker rm -f` 命令，退出码 1 |
| 进程 | `.pid` JSON v2：owner / pid / startedAtEpochMs / token / commandLine | owner 匹配 + 进程命令行包含 token + 启动时间在 15s 容差内 | 拒绝发信号；旧式纯数字 PID、损坏记录、PID 复用、命令不符均拒绝 |
| 新一轮启动 | 固定名容器/进程必须不存在 | 存在即拒绝，提示 `--cleanup`（已记录 owner）或人工删除（无 label） | 不覆盖、不删除、不杀 |
| `--cleanup` | `.local-dev/hc03/run-owner.json` | 必须存在；逐个资源按上述规则校验 | 任一不匹配则拒绝该资源并退出码 1 |
| `--attach` | owner 文件 + 容器/进程身份 | 缺 owner 文件、容器 label 不符、进程记录不可验证均拒绝 | 不启动 |

新增/修改文件：

- [`scripts/perf/hc03/ownership.mjs`](../../../../../scripts/perf/hc03/ownership.mjs)：纯决策函数
  （`decideContainer` / `decideProcess` / `parsePidRecord` / `commandToken` / `newOwnerId`）。
- [`scripts/perf/hc03/lib.mjs`](../../../../../scripts/perf/hc03/lib.mjs)：`inspectContainer`、
  `assertContainerAccess`、`removeOwnedContainer`、带 label 的容器创建、`lookupProcess`（Windows
  `Get-CimInstance` / Linux `/proc`）、v2 PID 记录、`stopProc`/`killProc` 校验。
- [`scripts/perf/hc03/runner.mjs`](../../../../../scripts/perf/hc03/runner.mjs)：`preflightFreshEnvironment`、
  `attachToExistingEnvironment`、`cleanupOwnedEnvironment`；移除启动时的无条件 `teardown`。
- [`scripts/tests/hc03-ownership.test.mjs`](../../../../../scripts/tests/hc03-ownership.test.mjs)：13 个
  纯函数用例（误删/误杀防护）。

### 3.2 自动化测试

```bash
node --test scripts/tests/hc03-ownership.test.mjs
# tests 13 / pass 13 / fail 0
```

覆盖：缺失容器允许创建；同一 owner 遗留容器拒绝并要求 `--cleanup`；无 label/他人 owner 拒绝并
给出人工命令；`attach`/`operate`/`delete` 的允许与拒绝矩阵；旧式 PID 拒绝；owner/命令/token/
启动时间不匹配拒绝；死进程只清记录不发信号；PID 记录解析；token 提取。

脚本全量回归：`npm run test:scripts`（含既有 scripts 测试）。

### 3.3 手工拒绝演练

| 演练 | 结果 |
|---|---|
| 无 owner 文件执行 `--cleanup` | 拒绝，退出码 1，未删除任何容器/进程 |
| 人为创建**无 label** 的固定名容器后启动 | 拒绝并打印 `docker rm -f ai-costops-hc03-mysql`；容器保持 `created` 未被删除 |
| 人为写入旧式纯数字 PID（指向真实存活进程） | 拒绝，明确提示旧式 PID 不可验证；受害进程仍存活；未发信号 |
| 正常 setup | 容器带 3 个 owner label；PID 文件为 v2 JSON（owner/token/start time） |
| 正常 `--cleanup` | 逐个校验后停止 4 个进程、删除 2 个容器，退出码 0 |
| F03a SIGKILL smoke | 使用加固后的 `killProc` 身份校验完成 kill；见下节结果 |

### 3.4 HC-03 smoke 结果

| 命令 | 结果 |
|---|---|
| `node scripts/perf/hc03/runner.mjs --quick --scenario=none --skip-build --keep-running` | PASS（setup + baseline，owner/label/pid 记录均正确） |
| `node scripts/perf/hc03/runner.mjs --cleanup` | PASS（校验后清理，退出码 0） |
| `node scripts/perf/hc03/runner.mjs --quick --scenario=F03pre --reps=1 --skip-build` | **PASS**：加固后的 `killProc` 在校验 owner、`gateway-1.1.0.jar` token、进程启动时间后执行 kill；请求停在 `PLANNED`、无预留、Provider 0，TTL 后转 `FAILED_PRE_DISPATCH`，新请求 200；退出时 `--cleanup` 逐个校验后停止 4 个进程并删除 2 个容器 |

> 本轮**不重跑**全部 27 轮：所有权加固不改变故障注入的时序/事务语义，且已有证据基线
> `78ee975` 仍然有效。F03a 的 kill 路径被加固代码直接影响，因此单独 smoke 复验。

## 4. 证据与表述修正

1. **R-3 修正**（原表述把“事务注入点覆盖回滚”当作微秒级崩溃边界的等价证明，不准确）：
   - 进程在“数据库 commit 成功、但 HTTP 响应尚未返回”之间被 SIGKILL 的微秒级窗口，仍是
     **进程级 `NOT_VERIFIED`**。
   - 实际防重复结算的保护是**数据库唯一约束 + 终态幂等**：
     `gateway_settlement (org_id, request_id)` / `(org_id, usage_fact_id)` 唯一、
     `ledger_posting (org_id, posting_key)` 唯一；已 `SETTLED` 的结算再次进入直接返回既有结果。
   - 事务注入点测试（`GatewaySettlementTransactionIntegrationTest` 的 5 个阶段）证明的是
     **回滚完整性**，不等价于上述窗口的进程级证明。
2. F02 的 30 s Hikari fail-closed 仍如实记录，本 PR 不改产品默认值。
3. F06 真实 CLOSING 窗口未命中，保持 PARTIAL，不改写为“全场景高可用 PASS”。



