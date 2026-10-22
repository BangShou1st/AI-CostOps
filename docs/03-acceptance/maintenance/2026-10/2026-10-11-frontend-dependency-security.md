# 前端依赖安全修复与 CI 基线核对（2026-10-11）

## 问题与修复

远程主分支最近一次普通 CI 成功，但 2026-10-05 的 Security 工作流失败：Trivy 在前端锁文件中报告 Axios 1.19.0 的 7 项 HIGH 漏洞。CodeQL 两个作业成功。失败详情：[Security run 37321154823](https://github.com/BangShou1st/AI-CostOps/actions/runs/37321154823)。这不是新增脚本 CI 的运行结果；核对时本地整理分支尚未推送，也没有 PR。

将 Axios 固定为 1.20.0，并保留完整性校验。官方发布说明包含运行选项与原型污染等安全加固，修复版本与扫描报告一致：[Axios 1.20.0](https://github.com/axios/axios/releases/tag/v1.20.0)、[HTTP/2 安全公告](https://github.com/axios/axios/security/advisories/GHSA-3pq3-5fj3-cg6v)。不少 Axios 报告涉及 Node adapter；这里不据此声称浏览器部署可直接被利用，锁文件仍需满足依赖门禁。

官方 npm 审计进一步报告开发工具和传递依赖问题。将 Vitest 固定为 4.1.11；更新允许版本范围内的 brace-expansion 5.0.12、source-map-js 1.2.2、undici 7.30.0。没有使用 force、忽略漏洞或增加顶层 override。Vitest 更新同时解析了 Chai、tinyrainbow 与 sourcemap-codec 的兼容版本，未增加或删除锁文件包条目。

参考：[Vitest 公告](https://github.com/advisories/GHSA-82fw-gwwq-j7x9)、[brace-expansion 公告](https://github.com/advisories/GHSA-q2hr-2g5m-vwhr)、[source-map-js 公告](https://github.com/advisories/GHSA-68fv-2mgg-jv7q)、[undici 公告](https://github.com/advisories/GHSA-3wwx-pv8p-q78v)。

## 审计证据

本机默认 npm 镜像不实现 audit 接口，返回 404；该结果不作为安全通过证据。使用命令级 `--registry=https://registry.npmjs.org` 查询与审计，没有修改用户全局 npm 配置。最终官方 npm audit 覆盖生产与开发依赖，报告 0 漏洞。

使用与 CI 相同的 Trivy 0.73.0，在同一份新下载的漏洞数据库上扫描更新前后锁文件：

| 扫描 | 结果 |
| --- | --- |
| 原提交前端锁文件，生产依赖 | 7 项 HIGH，退出 1，均为 Axios |
| 更新后前端锁文件，包含开发依赖 | 0 项 HIGH/CRITICAL，退出 0 |
| 1644 个已跟踪文件的独立快照，vuln/misconfig/secret，HIGH/CRITICAL | 退出 0；未包含用户未跟踪文件、本地日志或运行配置 |

最后一项按文件逐一复制完整路径，未使用早期解包失败的 tar 快照作为证据。仓库扫描与现有 Security 文件系统作业一样使用 offline-scan，不证明三个 Docker 镜像扫描、CodeQL 或远程工作流已通过。

日志与 JSON 在 gitignore 的 `.local-dev/ci-validation/`：`npm-audit-final.json`、`security-scan/reports/before.json`、`final.json`、`repository-complete.json`。Trivy 容器均为独立命名的 `--rm` 容器。

## 前端回归与测试并发

首次普通前端回归为 492 通过、45 失败，失败集中于超时与异步等待，当时没有限制 worker 数。限制为两个 worker 后同一套 71 文件、537 项测试全部通过；未修改断言、测试超时或隔离策略。随后把 `maxWorkers: 2` 写入现有 Vitest 配置，让普通本地命令和 CI 使用相同的并发上限，并复跑普通命令。

Vitest 默认非 watch 模式使用全部可用并行度，固定上限会延长高性能机器的测试耗时，但可以降低同时加载多套 jsdom/Ant Design 的资源竞争。配置含义见 [Vitest maxWorkers](https://vitest.dev/config/maxworkers)。这里不将第一次全部失败归因于 Axios，也没有绕过失败用例。

最终普通命令 `npm run test -- --run`：71 文件、537 项通过，耗时 190.97 秒；`npm run lint` 和 `npm run build` 通过。`npm ci --dry-run --ignore-scripts` 确认包清单与锁文件一致。最终日志为 `.local-dev/ci-validation/frontend-default-tests.log`，命令参数探针日志为 `frontend-final-tests.log`。

没有变更应用页面、HTTP 包装、接口或后端源码；未重跑 Java 全量测试，其现有完整证据见开发任务入口审查。远程 PR 的实际门禁尚未运行，不能用本地结果代替。
