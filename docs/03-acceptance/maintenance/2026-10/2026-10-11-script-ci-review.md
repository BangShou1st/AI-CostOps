# 日常脚本 CI 接入验收（2026-10-11）

## 变更范围

在现有 CI 工作流中新增 `scripts-unit` 的 Ubuntu/Windows 矩阵，以及 Ubuntu `scripts-lifecycle`。两个作业使用 Node 24.14.0，分别生成 JUnit XML，上传步骤使用 `if: always()`；未配置忽略测试失败。生命周期作业预先拉取原测试使用的 Redis 镜像，避免把镜像首次下载计入单次 CLI 启动超时。

保留原有全部作业内容与并行关系，不合并成根目录完整 verify。没有修改产品代码、Maven 配置、前端代码或部署配置，没有推送或触发远程 CI。

测试辅助代码仅增加可选诊断输出：开发 CLI 的受控进程输出，以及临时 Compose 项目的 CLI 输出和最近 200 行容器日志。即使读取容器日志失败，也继续执行原有清理。未设置 `AICOSTOPS_TASK_TEST_ARTIFACT_DIR` 时不生成诊断日志。

## 本地验证

| 环境 | 范围 | 结果 |
| --- | --- | --- |
| Windows，Node 24.14.0 | 单元测试，双 reporter | 23 通过，0 失败 |
| Windows，Node 24.14.0，Docker Desktop | 生命周期测试，双 reporter 与诊断日志 | 2 通过，0 失败 |
| 隔离 Linux 容器，Node 24.14.0-bookworm-slim，Compose 2.40.3 | 单元测试，双 reporter | 20 通过，3 项 Windows 专属跳过，0 失败 |
| 同一 Linux 环境，连接本机 Docker 引擎 | 生命周期测试，双 reporter 与诊断日志 | 2 通过，0 失败 |
| actionlint 1.7.12 | 全仓库 GitHub Actions 工作流 | 通过；Windows 未安装 ShellCheck，仅关闭其外部调用 |

Linux 使用只读脚本快照、可写报告目录和独立容器，启用 `--init`，验证了 POSIX 进程树退出与端口释放。没有修改用户 WSL/Docker Desktop 集成设置。这不是 GitHub hosted runner 的实际运行证据。

报告分别位于（均被 gitignore）：

- `test-results/scripts/windows-unit.xml`
- `test-results/scripts/windows/lifecycle.xml` 与同目录两个诊断日志
- `test-results/scripts/linux-unit.xml`
- `test-results/scripts/linux-lifecycle.xml` 与 `test-results/scripts/` 下两个诊断日志

命令使用 Node 内置 `--test-reporter=spec --test-reporter=junit`，分别指定 `stdout` 和对应 XML 路径，测试入口为 `scripts/tests/*.test.mjs` 或 `scripts/tests/*.integration.mjs`。报告已按 XML 节点核对测试、失败、跳过数量，不只依赖控制台摘要。

## 失败与清理验证

在忽略目录的独立测试副本中，于第一次成功启动临时 Compose 项目后注入一次 `assert.fail`。真实 Node 测试进程退出 1，XML 包含一个 `failure`，`infra-lifecycle.log` 保留 CLI 输出及容器日志；测试清理仍移除该项目的容器、网络和数据卷。该失败探针没有进入正常测试集。证据在 `test-results/scripts/failure-probe/`。

正常生命周期测试验证重复启动复用容器，`infra:down` 保留容器和数据卷；只有测试最终清理会删除自身 UUID 项目的资源。验证完成后检查无 `aicostops-tasks-test-*` 容器或数据卷残留，并移除本轮 Linux 工具卷。原有 Backend 8080、Frontend 5173 的 HTTP 检查仍通过，Gateway 8081 仍空闲。

与 HEAD 的文本比较确认 `backend-unit` 及其后的原有 CI 作业完全不变；`git diff --check` 通过。本轮没有重复运行 Java/前端完整 verify，其上一轮证据见 `2026-10-11-development-tasks-review.md`。

## 后续

下一次 PR 核对三个新增平台作业的实际状态与下载产物。尚未确认远程 CI 成功，也未修改仓库分支保护的 required checks。
