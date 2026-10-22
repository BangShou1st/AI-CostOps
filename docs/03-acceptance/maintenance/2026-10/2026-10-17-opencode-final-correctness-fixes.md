# OpenCode 两项正确性收尾

日期：2026-10-17
分支：`codex/history-coverage-20261012`
基线：`b44f634f67510eeb15de832974866efc6173c114`

## 修复

- V30 保持原样，避免已执行迁移的 Flyway checksum 冲突。新增 V31：按账户现有最大 profile 版本号加一，保存旧行当前配置及 ID、将其退休，再创建 `/inference/v1` ACTIVE profile。profile 关联记录仍指向旧 ID；Gateway 路由解析继续按 ACTIVE profile ID 选择新端点。
- OpenCode `complete()` 只有在收到非空文本 delta 和上游 `Done`（SSE `[DONE]`）后才成功。缺少结束标记或有效文本会返回 `MALFORMED_PROVIDER_RESPONSE` / `BILLABLE_POSSIBLE`；流传输异常沿用既有费用不确定性分类，适配器不重试。流式入口和其他 Provider 未改。

## 数据风险

本机 MySQL 在 3306 端口运行，但项目账号只读查询 Flyway 历史时认证失败；项目 local 配置的 3307 未运行，无法确认持久库是否执行过 V30。因此按“V30 可能已执行”处理，没有改写 V30。

V30 会把 ACTIVE profile 的 `base_url` 原地改为 inference。Gateway 的冻结 route attempt 只引用 profile ID，不保存端点快照。若持久库曾在 V30 与 V31 之间承载请求，同一个 profile ID 可能对应迁移前后的路由端点，原始历史地址无法可靠地恢复或重新分配。V31 不猜测回填，也不改旧行的配置字段；只切换状态并创建新版本。该历史风险需由独立复审确认持久库状态。

## 定向测试

- 修复前：MySQL 迁移集成测试 1 项失败（V30 后仍复用旧 profile ID）；OpenCode 聚合测试 2 项失败（缺 `[DONE]`、仅 `[DONE]` 均错误成功）。
- 修复后：Backend V30/V31 MySQL Testcontainers 集成测试 1 项通过；Gateway OpenCode Mock Server 测试 9 项通过；Gateway profile 路由集成测试 3 项通过。
- 测试不访问真实 OpenCode。

## 完整验收

- `npm run verify`：退出码 0；根脚本单测 27/27、生命周期测试 2/2。
- Backend Maven verify：单元/架构测试 685 项（失败 0、错误 0、跳过 1），集成测试 1257/1257；退出码 0。
- Gateway Maven verify：单元测试 217/217、集成测试 94/94；退出码 0。
- Frontend：Vitest 547/547；lint 和生产构建通过。
- 未修改或发布共享 Starter；未执行真实 OpenCode 请求。
