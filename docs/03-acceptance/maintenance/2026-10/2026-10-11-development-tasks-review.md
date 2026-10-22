# 2026-10-11 开发任务入口审查

## 修改范围

根 `package.json` 和 `scripts/tasks.mjs` 统一日常任务，`scripts/dev/*.ps1` 与 `*.sh` 只负责定位仓库并调用同一实现。保留历史里程碑脚本，未调整业务模块或生产 Compose 镜像。

修复原入口漏启 Gateway、未显式激活 local profile、遗漏 Gateway 测试及排除架构测试的问题。`test` 保留 Maven 原有单元/集成分离规则；`verify` 对 Backend、Gateway 执行完整 Maven 生命周期，再执行前端测试、lint 和生产 build。

`dev` 先检查 IPv4/IPv6 端口，启动本机三端并等待 HTTP 就绪。SIGINT/SIGTERM、进程提前退出或就绪超时会清理本次创建的进程树，不按端口或进程名称终止现有实例。Windows 用 Node 管理进程组、附属 cmd 执行 Maven wrapper，并传回嵌套进程的输出及失败状态。

Maven 在同模块目录下使用临时 POM，仅重定向 build.directory 到 `.local-dev/builds/<任务>/<模块>/target`，保留 basedir 和标准 target 目录形状。原模块 target 中的运行产物不被覆盖；任务结束后清理临时 POM。根 `.env` 与环境变量映射为 native local 配置，配置值不由 doctor 输出。

基础设施使用当前 Compose 项目的 mysql/redis/minio，支持重复 up、健康等待、status 和保留容器/数据卷的 stop。没有 `.env` 或端口被项目外监听者占用时拒绝启动，不自动接管独立容器，也不停止 backend/frontend 容器。

## 验证证据

| 验证 | 当前结果 |
| --- | --- |
| 最终脚本单元/命令测试 | 23 项通过 |
| 真实 dev CLI 生命周期 | HTTP 夹具三端全部就绪；SIGINT 后 6 个已记录应用/孙进程全部退出，端口释放，临时 POM 消失；已复跑最终参数断言 |
| 真实 infra CLI 生命周期 | 独立 Compose 项目、三个 Redis 夹具：外部端口冲突时不创建容器；重复 up 保持三个容器 ID；stop 后三个容器及三个数据卷保持；测试结束后清理自身项目 |
| PowerShell 包装 | 从其他工作目录运行，缺少配置时非零退出；命令级测试通过 |
| Bash 包装 | Git Bash 执行三项 dry-run；实际缺少配置时退出 1；语法检查通过 |
| doctor/status | Node 24.14.0、JDK 21.0.10、npm 11.9.0、Docker、wrappers、前端依赖通过；8080/5173 HTTP 通过，8081 空闲 |
| 真实 Maven 失败传播 | 无效生命周期返回 exit 1，没有被吞成成功 |
| Backend 完整 verify | 单元/架构 610 项（跳过 1），集成 1,196 项，失败/错误 0；182 个集成报告，failsafe-summary 一致；包含 package/repackage |
| Gateway 完整 verify | 单元/架构 173 项、集成 84 项，失败/错误/跳过 0；31/20 个报告，failsafe-summary 一致，包含打包/repackage |
| 前端门禁 | 71 文件、537 项全部通过；lint 与生产 build（含 TypeScript 检查）通过 |
| 完整任务状态 | `npm run verify` 退出 0；两个临时 POM 已清理，原后端/前端 HTTP 仍通过，原四个容器保持运行 |

执行命令：

```powershell
npm run doctor
npm run status
npm run test:scripts
npm run test:scripts:integration
npm run dev -- --dry-run
npm run verify -- --dry-run
npm run verify
```

完整验证的独立 Maven 输出：

```text
E:/project/AI-CostOps/.local-dev/builds/verify-1791694128380-4ae40863-a2be-4a3e-9e84-d6d4c8de413c
```

完整任务启动时尚为 13 项脚本测试。此后补完命令与生命周期场景，最终 23 项单元/命令和 2 项生命周期测试单独执行通过，并加入最终 verify 入口。Java 业务源码、Maven 生命周期及前端门禁在该执行期间未改动。早期 Windows 包装的日志传递缺陷已修复，最终实现通过真实 Maven 版本/失败传播及嵌套 batch 输出回归；完整任务中的 Maven 结果以独立输出目录内的 XML 和产物核对。

完整任务日志保留在根 `.local-dev-task-verify.log`（gitignore）。后端原 `target/backend-1.1.0.jar` 的修改时间仍为 2026-10-11 01:25，本轮没有覆盖它。两个隔离 JAR 的 manifest 均为 Boot JarLauncher，包含各自 Start-Class 和 BOOT-INF/classes；新产物已生成，但本轮没有替换当前运行服务。

耗时较长的报表基准阶段已确认测试 JVM 仍存活并执行真实批量 SQL；只读核对了本轮 Testcontainers 数据库的夹具行数增长，没有减小原有 10,000 行数据规模、跳过基准或修改数据库参数。

## 验证边界

生命周期夹具验证脚本控制行为，dev 夹具不运行真正的 Spring/Vite 业务，也不访问真实财务数据；infra 夹具不验证生产 MySQL/MinIO 镜像。原有独立 MySQL/Redis/MinIO 容器、8080 后端及 localhost:5173 前端保留。没有创建根 `.env`、更新运行实例、推送或合并。

本轮只验证 Windows/PowerShell 和 Git Bash 包装，没有声称已完成 Linux/macOS 原生任务验收。常用命令、配置优先级及产物位置见 [scripts 说明](../../../../scripts/README.md)。

## 下一步

CI 现有工作流已分别覆盖两个 Maven 模块的单元、架构、集成，以及前端检查；下一步接入新增脚本单元及生命周期测试，核对其门禁与产物报告，不需要把现有并行作业强行合并成一个长作业。
