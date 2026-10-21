# OpenCode Zen v1.4.2 接入修复

上游：[README](https://github.com/BangShou1st/opencode-zen/blob/v1.4.2/README.md)、
[CHANGELOG](https://github.com/BangShou1st/opencode-zen/blob/v1.4.2/CHANGELOG.md)。
固定 tag `v1.4.2`，对应提交 `9295f0cb5e19cd51026449e7b06fb618b82b0c1f`。

## 变更

- Backend、Gateway 统一使用
  `com.github.BangShou1st.opencode-zen:opencode-zen-spring-boot-starter:v1.4.2`，
  从公开 JitPack 解析；删除旧 GitHub Packages 仓库。
- CI、CodeQL、镜像构建与 Compose 不再要求 `ZEN_MAVEN_TOKEN` 或 settings 文件。
  Dockerfile 仍支持可选的 Maven settings secret，供镜像/代理配置使用；
  `npm run maven:settings` 保留为可选辅助命令。
- 两端采用共享库的 OpenCode 2.x 身份与 `shell/read` 信封；控制面保留共享的
  `Accept: */*`，避免重复发送大小写不同的 Accept。网关写入共享 Header 时覆盖同名值。
- 接受共享准入策略 `2026-10-21-v2` 的九个免费 Chat 模型；控制面补齐五个新模型的
  Chat/VERIFIED_FREE 元数据，防止共享准入通过后仍被旧元数据拒绝。
- `space-bunny-free` 保留历史分类但失去准入。付费、未知模型和决策端点
  `jev-1.13-free` 仍不能进入免费调用流程。
- 分类本身不创建生产价格、路由或账号。现有探测、晋升与独立 Gateway 准入流程继续生效。

当前九个模型为 `beam-free`、`big-pickle`、`deepseek-v4.1-flash-free`、
`glm-5.3-flash-free`、`longcat-2.5-preview-free`、`mimo-v2.6-flash-free`、
`minimax-m3.1-flash-free`、`nemotron-3.5-lightning-free`、`nemotron-3.5-super-vl-free`。
这是采纳上游版本化复核结果，不是按 `-free` 后缀动态放行。

## 验证

- 不携带认证匿名下载两个发布 JAR：starter 261 字节，autoconfigure 44722 字节；
  autoconfigure JAR 包含 `zen-client-profile.json`、自动装配 imports 与免费准入策略。
- Backend OpenCode 与传输相关测试：70 项通过，包含真实 MySQL/Redis、Spring 自动装配、
  模型发现/晋升/拒绝以及历史数据库迁移。
- Gateway OpenCode、执行上下文及网络边界测试：58 项通过，包含真实 MySQL/Redis、
  独立准入阻断、HTTP 抓取的 2.x Header/工具正文与非流式聚合。
- 任务脚本测试：27 项通过。
- 两端全量单元及架构测试：Backend 693 项（692 通过、1 项按既有配置跳过），
  Gateway 229 项全部通过；新增控制面 Header 回归测试也通过。
- 默认 Compose 与 operational overlay 合并配置校验通过，无 settings 文件依赖。
- Backend Docker 镜像无 token、无 Maven settings 构建成功：
  `docker build --tag ai-costops-backend:zen-v142-check backend`。
  首次下载 Maven Central 的 `commons-text` 遇到 TLS 中断；原命令重试后完成，
  容器中直接从 JitPack 解析两个 v1.4.2 模块。未运行镜像或执行部署。

## 真实上游请求复核

用户要求真实请求成功后再提交、推送，因此追加一次免费 `big-pickle` 请求。
直接复用当前编译代码的 `ModelProbeService.openCodeZenProbeBody`、
`OpenCodeZenTransportHeaders` 和 `ProviderControlPlaneTransport`，使用项目的
DIRECT_ONLY 传输策略，POST 到 `https://opencode.ai/inference/v1/chat/completions`。

返回 HTTP **429**，Content-Type 为 `application/json`，共享库分类 `RATE_LIMITED`：

```json
{"type":"error","error":{"type":"FreeUsageLimitError","message":"Rate limit exceeded. Please try again later."}}
```

这一请求没有取得 HTTP 200 或有效 SSE；该响应只能证明这次 `big-pickle` 请求被限流，
不能据此推断其他模型不可用。收到 429 后没有自动重试或切换出口。

### 对照成功消费方后的验证

用户随后指定参考 `E:/project/shared-libs/opencode-zen-consumer-app`。
其 `LiveZenIT` 报告确认四项真实测试通过（非 skipped），实际推理模型为
`minimax-m3.1-flash-free`，`ZenChatService` 使用 400 token；两项目均采用直连。
按用户要求，对同一成功案例进行两端验证，没有修改网络出口：

| 项目路径 | 实际调用 | 结果 |
| --- | --- | --- |
| Backend | 现有 ModelProbeService 正文（16 token）、OpenCodeZenTransportHeaders、ProviderControlPlaneTransport | HTTP 200，text/event-stream，返回 `{"ok":true}` 和 `[DONE]`；CHAT_COMPLETIONS、SSE_STREAMING、USAGE、STRUCTURED_JSON 均 VERIFIED |
| Gateway | 现有 OpenCodeZenChatAdapter.complete，`minimax-m3.1-flash-free`，400 token，提示 `Reply with exactly: PONG` | HTTP 200，聚合回复 `PONG`，finish_reason=stop；usage 为 prompt=855、completion=4、total=859 |

Gateway 验证未配置存储凭据，采用共享免费凭据；真实调用直接复用项目适配器、公共地址校验、
DIRECT_ONLY 与 SSE 聚合实现。Backend 同样直接复用项目编译代码，而非仅调用共享库示例。
这证明当前接入在成功案例模型上可以取得 HTTP 200 和有效内容，不承诺所有模型实时可用。
临时启动程序和完整响应仅保存于被忽略的 `.local-dev/`，没有创建生产账号、价格、路由或部署。
