# 共享库 Provider 退役记录

## 元数据

- **日期**：2026-11-14
- **责任人 / AI**：Codex
- **关联模块**：`backend`、`gateway`、`frontend`、`scripts`、`docs`
- **关联事项**：OpenCode Zen / Kilo Gateway 外部共享库退役；MiMo 模型路由退役
- **变更类型**：维护期能力下线与依赖清理

## 变更概述

用户明确决定不再维护两个外部共享库，并准备删除其上游仓库：

- `com.github.BangShou1st.opencode-zen:opencode-zen-spring-boot-starter:v1.5.4`
- `com.github.BangShou1st.kilo-gateway:kilo-gateway-spring-boot-starter:v0.2.1`

因此本仓库不再以“本地收编”方式保留这两个库，而是连同其唯一消费方一起下线：

1. 移除两个 JitPack 依赖、`jitpack` 仓库、Jackson 2 兼容依赖（Gateway）及相关版本属性。
2. 删除 Backend 的 OpenCode/Kilo manifest、探针适配、Relay/Header 适配与所有相关测试。
3. 删除 Gateway 的 OpenCode/Kilo adapter、地址解析器、免费层准入屏障与所有相关测试。
4. `ProviderTemplateRegistry` 收敛为单一 `CUSTOM_OPENAI_COMPATIBLE` 模板；连接创建只走自定义 OpenAI 兼容路径。
5. Gateway 路由删除 keyless 免费模型、内置 UA、relay loopback 与 `FREE_ADMISSION_REQUIRED` 分支。
6. MiMo 模型路由 adapter 同步移除；MiMo 账单工作簿导入与 canonical cost 归一化保留，因为其处理的是既有财务账单事实，不是模型接入。
7. 新增 Flyway `V33__retire_shared_library_providers.sql`，仅将 `OPENCODE_ZEN`、`KILO_GATEWAY`、`MIMO` 的 `provider_catalog.status` 置为 `DISABLED`；账户、连接档案、定价、费用、用量与账本历史均不删除。
8. 前端 Provider 展示、benchmark fixture、E2E 夹具与当前产品/运维/API 文档同步收敛为自定义 OpenAI 兼容端点。
9. `DevGatewayBootstrap` 恢复一个**中性的自定义 OpenAI 兼容开发路由**（不再绑定任何厂商），仅用于本地 dev 与 E2E 的路由策略/隔离验收。

## 验证环境与执行命令

执行环境：Windows PowerShell，Java 21，Node 24。

```powershell
cd backend
.\mvnw.cmd -B -DskipTests compile
.\mvnw.cmd -B -DskipTests test-compile
.\mvnw.cmd -B "-DexcludedGroups=architecture,integration" test
.\mvnw.cmd -B "-Dgroups=architecture" test
.\mvnw.cmd -B "-Dit.test=ProviderHubApiIntegrationTest,DevGatewayBootstrapIntegrationTest" "-DfailIfNoSpecifiedTests=false" verify

cd ..\gateway
.\mvnw.cmd -B verify

cd ..\frontend
npm test -- --run
npm run lint
npm run build

cd ..
npm run test:scripts
npm run verify:links
```

## 关键断言

- 两个共享库坐标不再出现在 `backend/pom.xml`、`gateway/pom.xml` 或前端/脚本构建配置中。
- `backend/src/main` 与 `gateway/src/main` 不再引用 `com.sharedlibs.*`。
- Gateway 不再注册 `OPENCODE_ZEN`、`KILO_GATEWAY`、`MIMO` adapter；候选路由对这些 adapter code 会以 `ADAPTER_UNAVAILABLE` 失败闭锁。
- V33 迁移后三个旧 catalog 行仍存在，但状态为 `DISABLED`；历史账户与财务事实保持可追溯。
- 前端“Provider Gallery”只展示自定义 OpenAI 兼容模板，且继续提示“连接可用不代表可正常路由”。
- Backend 单测 644 项通过（1 skipped）；ArchUnit 36 项通过；定向集成验证 12 项通过，Flyway 校验 33 个迁移并成功应用 V33。
- Gateway 全量 `verify` 通过：单测 164 项 + 集成 99 项，覆盖预算、路由、流式、结算与适配器回归。
- Frontend 73 个测试文件 / 560 项通过；lint 与生产构建通过。
- 根脚本测试 53 项通过；全仓 Markdown 262 个文件、541 个链接 0 死链。

## 结论与影响边界

**结论：PASS（代码与文档层）**

- 本次是显式能力退役，不是 Provider 增加或框架替换。
- 现有历史 `docs/03-acceptance/` 证据按仓库规则保留，不回写为“从未支持”。
- MiMo 账单导入能力仍保留；若后续要求彻底移除 MiMo 财务导入，应作为独立的、涉及历史数据兼容性的维护变更单独立项。

