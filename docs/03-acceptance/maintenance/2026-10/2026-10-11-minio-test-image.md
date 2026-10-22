# 2026-10-11 MinIO 测试默认镜像收尾

## 范围与修改

针对成本分析整理验收中遗留的测试环境问题，统一修改两类 MinIO 测试容器的默认镜像。生产配置、开发 Compose、成本分析业务及前端代码不在本轮修改范围内。

- 新增 `backend/src/test/java/com/aicostops/testsupport/MinioTestImage.java`，集中定义固定摘要及既有覆盖参数。
- `MinioContainerSupport` 与 `MinioAuthenticationContainersSupport` 共用该定义；保留原端口、凭据、服务命令、健康检查与动态属性注册。
- 默认镜像为 `cgr.dev/chainguard/minio@sha256:f74600a1a46330cdbda1ef760d17a96bd6e0f4a6f0a2c49792ca3ee7e4c6fa18`，不是浮动 latest。
- 仍支持 `-Daicostops.test.minio-image=<镜像名或摘要>`，便于受限网络及内部仓库使用。
- 根 README 补充默认值、覆盖与摘要更新说明。

选择依据是 [Chainguard MinIO 官方镜像说明](https://images.chainguard.dev/directory/image/minio/overview) 及实际 registry 检查，不依据一次 Quay 拉取失败断言上游镜像永久不存在。

## 匿名拉取验证

使用新建的空 Docker 配置目录执行 `docker --config <空目录> manifest inspect <固定摘要>` 和 `docker --config <空目录> pull <固定摘要>`，两项均退出 0。

返回的 OCI image index 含 linux/amd64、linux/arm64。pull 返回同一摘要。此次 pull 复用了本地已有层，因此证明了匿名 manifest 访问与 pull 请求成功，不声称已模拟另一台空镜像缓存主机的全量下载，也不保证未来外部仓库永远可用。

## 完整 Maven 验证

完整验证通过，进程退出 0，`BUILD SUCCESS`，耗时 17 分 22 秒。

| 项目 | 本轮实际结果 |
| --- | --- |
| Surefire | 82 个报告，609 项，失败 0、错误 0、跳过 1（Windows 平台条件） |
| Failsafe | 182 个报告，1191 项，失败 0、错误 0、跳过 0；failsafe-summary 一致 |
| 三类补测 | 流水线 4、建议命令 3、Advisor 风险证据 3，全部通过 |
| 镜像默认值 | 启动日志使用固定 cgr.dev 摘要；集成报告中没有显式 aicostops.test.minio-image 属性 |
| 打包 | jar 与 Spring Boot repackage 均成功 |
| 产物内容 | 9 个拆分类均存在，旧总服务类不存在；Advisor 字节码依赖新风险查询服务 |
| 产物一致性 | 抽查分析、Generator、风险查询与 Advisor 四类，JAR 内与本轮编译输出 SHA-256 相同 |
| 本地服务 | 原 PID 52352 仍监听 8080，最终健康检查 UP；未重启，未遗留 8099 验证实例 |

执行命令（工作目录 backend）：

```powershell
.\mvnw.cmd -B -f .minio-default-verification.pom.xml verify
```

临时 POM 已移除。新产物和报告保留在
`C:\Users\32962\AppData\Local\Temp\aicostops-minio-verification-ce80654ade9845eea915f46d0ed05008\target`。
常规构建仍使用原 `pom.xml` 执行 `.\mvnw.cmd -B verify`，不需要传镜像覆盖参数；若本机后端正在占用常规 JAR，需安排打包时机或使用独立输出目录。

为保留现有 8080 后端运行及原产物，使用与原 POM 相同的临时验证 POM，仅把 build.directory 设置为临时目录中的 `target`。源码目录、资源、依赖、插件和测试配置均不变，执行完整 `verify` 生命周期，没有传 MinIO 镜像覆盖参数。临时 POM 完成后移除。

首次尝试把输出设置为 `backend/target/minio-default-verification`，架构测试的 DO_NOT_INCLUDE_TESTS 依赖标准 `target/test-classes` 路径，因而误纳入测试类并导致 7 项架构失败。已改用 `<临时根目录>/target`，保留标准目录形状；没有修改、跳过或放宽架构规则。

原有 `backend/target` 的验收报告和运行产物不被这次验证覆盖。验证日志为 `%TEMP%/aicostops-minio-default-verify-20261011.log`，首次尝试日志以 `-attempt1.log` 单独保留。

## 成本分析复核

补测源码已使用真实 Generator spy、事务内非零断言、事务后零行断言，以及成功提交对照用例。Advisor 预算风险夹具已使用注入的 Clock。已有报告为 609 项单元（跳过 1）及 1191 项集成，失败错误为零；已有可执行 JAR 包含拆分类、不含旧总服务，启动/路由日志与 8080 健康检查可核对。

实施 AI 留下的变异验证记录作为其执行证据保留，本轮不声称重新执行了该变异过程。本轮完整验证再次执行了补测的最终正常源码，10 项全部通过。

同时修正文档数量表述：三个补测文件最终为 10 项；Advisor 为 4 个 URL、5 个 HTTP 方法映射，profile 同时支持 GET/PUT。这些文档修正不改变接口。

本轮没有改动前端产品代码或重新执行前端/UI 验收；原前端 38 项结果沿用实施轮记录。

## 下一步

本轮默认镜像和成本分析收尾通过后，再单独设计 Advisor 证据构建职责整理；不继续拆当前分析服务或同时处理网关财务事务。
