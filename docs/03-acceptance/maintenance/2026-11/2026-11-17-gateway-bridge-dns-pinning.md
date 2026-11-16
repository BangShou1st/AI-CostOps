# Gateway ai-model-bridge v0.2.1 连接期 DNS 防护验收

## 元数据

- **日期**：2026-11-17
- **责任人 / AI**：Codex
- **关联模块**：`gateway`、`ai-model-bridge-core`
- **关联缺陷**：v0.2.0 transport 只做请求前 DNS 预检，验证与 socket 建连之间存在 DNS rebinding TOCTOU 窗口
- **上游版本**：`ai-model-bridge v0.2.1`
- **上游提交**：`7b1cca1`（分支 `fix/transport-connect-pinning`，tag `v0.2.1`）
- **JitPack**：`v0.2.1` 构建成功，core / starter / server 制品均可公开下载

## 变更概述

`ai-model-bridge-core` 新增连接期公网 DNS 解析器：`PUBLIC_HTTPS_ONLY` 策略下，
`HttpTransport` 使用 Apache HttpClient 5 的 `DnsResolver` 在真正建连前重新解析
并校验完整 DNS 答案；private、loopback、link-local 与 cloud-metadata 地址会在请求
体发送前被拒绝。`ALLOW_LOOPBACK` / `ALLOW_PRIVATE_NETWORK` 继续使用原 JDK transport，
以保留本地 mock 与显式私网端点的既有测试能力。

AI-CostOps Gateway 将 bridge 依赖升级到 `v0.2.1`，并在 `allow-private-endpoints=false`
时使用新的 pinned 构造路径；开发/测试的私有端点路径保持原行为。redirect、超时、
SSE 取消与响应大小边界不变。

## 验证环境与执行命令

环境：Windows 11、Java 21.0.10、Maven Wrapper、Docker Desktop。

```powershell
# 上游 ai-model-bridge v0.2.1
.\mvnw.cmd -B -ntp verify

cd gateway
.\mvnw.cmd -B verify
```

## 关键断言与证据

- 上游 `ai-model-bridge` 全量 `verify` PASS：core 73、starter 19、server 14。
- `PublicOnlyDnsResolverTest`：同一主机第一次解析为公网 `93.184.216.34`，第二次翻转为
  私网 `10.0.0.7` 时，连接期 resolver 抛出 `UnknownHostException`；loopback、
  metadata 与 RFC1918 地址同样被拒绝。
- `PinnedHttpTransportTest`：pinned Apache transport 可完成 JSON GET 与 SSE 流式读取；
  响应不会被整体缓冲后再解析。
- AI-CostOps Gateway 全量 `verify` PASS：163 个单元/架构测试 + 99 个集成测试。
- GitHub 远程复核（2026-11-17）：`refs/heads/main` 与
  `refs/heads/fix/transport-connect-pinning` 均指向
  `7b1cca138024e27447eaeabcda7c46165bd8b0de`；`refs/tags/v0.2.1^{}` 解引用后同为
  `7b1cca1`。JitPack `v0.2.1` 构建日志为 `BUILD SUCCESS`，core JAR 返回
  HTTP 200（277,101 bytes），日志首行版本为 `v0.2.1-0-g7b1cca1` 并记录
  `commit 7b1cca138024e27447eaeabcda7c46165bd8b0de`；starter / server POM
  同样可公开下载。
- 使用全新空 Maven 仓库（`-Dmaven.repo.local=<temp>`，不使用本机预装制品）
  执行 `dependency:get`，2026-11-17 复验 `BUILD SUCCESS`（2:40），core JAR
  277,101 bytes 由 JitPack 解析；同一空仓库执行 Gateway `-DskipTests compile`
  也 `BUILD SUCCESS`（3:18，84 个源文件），证明干净环境可直接构建。
- 2026-11-17 Trivy 首轮扫描发现 bridge 传递依赖 `httpcore5` /
  `httpcore5-h2` 5.4.2 命中 CVE-2026-54399 / CVE-2026-54428（HIGH）；
  Gateway 通过 dependencyManagement 将两者覆盖到 5.4.3，`httpclient5` 5.6.1
  保持不变，依赖树复验为 5.4.3。
- 2026-11-14 记录的 DNS pinning 已知边界不再适用于 AI-CostOps Gateway；
  旧的 `OpenAiChatAdapter` / `PublicOnlyAddressResolverGroup` 仍保持删除，不恢复第二套 wire。

## 结论与影响边界

**结论：上游发布、JitPack 构建、干净 Maven 解析与 Gateway 定向验证 PASS；AI-CostOps CI / Security 待提交后确认。**

该修复恢复的是“连接时公网地址复验”，不改变 Gateway 的预算、路由、结算、账本或
API 契约。JitPack `v0.2.1` 已可被干净环境解析。
