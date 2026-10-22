# 草稿 PR 远程门禁复核（2026-10-11）

## 发布与首轮结果

用户确认发布本地整理分支后，创建 [草稿 PR #167](https://github.com/BangShou1st/AI-CostOps/pull/167)。未合并、部署或替换本地运行服务。

首轮提交 `82ca4ac` 的 [CI](https://github.com/BangShou1st/AI-CostOps/actions/runs/38122882599) 为 13 个作业成功、browser-e2e 失败；[Security](https://github.com/BangShou1st/AI-CostOps/actions/runs/38122882623) 的两个 CodeQL 作业成功，Trivy 镜像扫描失败。

新增脚本作业的产物已实际下载并核对 XML：Windows 23 项通过，Ubuntu 20 项通过、3 项平台专属跳过，生命周期 2 项通过；报告无失败，生命周期两个诊断日志均存在。下载目录为 `.local-dev/ci-validation/remote-script-artifacts/`。这补齐了上一轮只有本地 Linux 容器的证据。

## MinIO 拉取与健康检查

E2E 在启动 Compose 时失败，日志为 `minio Error unauthorized`，页面测试未开始。根 Compose 的旧 Quay 镜像引用仍未迁移，不能把此失败归因于应用页面。

根 Compose 改用已在集成测试及远程 runner 验证的 Chainguard MinIO 固定摘要。镜像内不含 curl，但包含 mc；健康检查改为 `mc ready local`，`MC_HOST_local` 明确指向容器内服务。保持命令、端口、凭据变量、卷名和健康检查时间配置。依据：[Chainguard 镜像说明](https://images.chainguard.dev/directory/image/minio/overview)。

用独立项目 `aicostops-minio-ci-probe-20261011`、独立网络和新建数据卷，按实际根 Compose 只启动 MinIO：

- `up -d --wait` 成功，容器 healthy，实际 mc readiness 成功。
- 使用非默认测试凭据创建桶并写入对象。
- 重启后再次 healthy；下载对象与原文件 SHA-256 相同，证明新数据卷可写并保留内容。
- 检查项目标签后只清理该测试项目的容器、网络和卷，未修改已有存储。

记录在 `.local-dev/ci-validation/minio-compose/`。新镜像默认 UID 65532，旧数据卷的内容和所有权迁移未验证；README 已要求复用前备份并核对权限，不自动 chown 或清理用户数据。

## 镜像内 Java 依赖

首轮 Security 的文件系统扫描成功，但后端 Boot JAR 镜像扫描报告 10 HIGH、2 CRITICAL。文件系统离线扫描不充分证明打包后的传递依赖安全。

将 Backend/Gateway 的 Boot 4.1.0 BOM 属性统一覆盖为：Spring Framework 7.0.9、Jackson 2 BOM 2.21.7、Jackson 3 BOM 3.1.7。采用整组 BOM 补丁版本，保留原 Netty/Tomcat 等覆盖，不改 Boot 大小版本、依赖模块或业务源码。

版本来源：[Spring CVE-2026-47884](https://spring.io/security/cve-2026-47884/)、[Spring CVE-2026-47890](https://spring.io/security/cve-2026-47890/)、[Jackson 2.21.7](https://github.com/FasterXML/jackson/wiki/Jackson-Release-2.21.7)、[Jackson 3.1.7](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.1.7)。Trivy 与厂商的严重等级有差异；Spring 公告涉及特定视图/SSE 片段配置，这里不声称本项目已经满足所有可利用前提。

本地启动完整 `npm run test`，输出在隔离目录，日志为 `.local-dev/ci-validation/java-patch-root-test.log`。首次拉取新依赖长时间停滞，核对进程归属后停止了该 Maven 进程；任务入口退出 1 并正常移除临时 POM。没有把该取消记录当作测试通过。修复提交的验证以对应 PR head 的远程结果为准。

## 完整集成测试收集

提交 `fc98c26` 的 CI 与 Security 均成功：14 个 CI 作业、3 个 Security 作业通过，E2E 实际 16 项通过，Trivy 文件系统及三个镜像步骤通过。但下载实际报告后发现收集范围不足：Backend 171 份报告、1167 项，相比此前完整 verify 的 182 份、1196 项少 29 项；Gateway 日志为 83 项，相比完整 verify 少 1 项。

原因是原 CI 命令 `-Dgroups=integration verify` 过滤掉没有 integration 标签的已有测试。漏收内容包括 ledger 入账、对账 API 和 Provider DNS 重绑定等；不能仅依据绿色状态声称它们已验证。POM 已用文件名将 Surefire 单元与 Failsafe 的 `*IntegrationTest` 分开，进一步标签过滤并非完整收集所必需。

因此将两个集成作业改为 `./mvnw -B verify`，保留完整 package/repackage 与所有 Failsafe 测试。该作业会再次运行单元及架构检查，这是换取完整生命周期的一项明确重复；原独立单元/架构作业及并行关系保留。后续报告必须达到实际源码的完整收集范围，不能用 fc98c26 的 1167/83 项作为最终验收数量。
