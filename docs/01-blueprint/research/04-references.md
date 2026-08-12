# 04. 参考资料

> 访问日期：2026-08-11
> 原则：Provider/标准事实优先使用官方一手来源。

## DeepSeek

1. DeepSeek FAQ — Usage by API Key / export package
   https://api-docs.deepseek.com/faq

本项目使用的关键事实：

- Usage 页面可按月 Export；
- 压缩包中有两个 CSV；
- `amount` 文件用于按 API Key 查看 usage detail。

---

## Kimi / Moonshot

2. Kimi — 建立并认证你的组织 / 管理项目及使用限额
   https://platform.kimi.com/docs/guide/org-best-practice

关键事实：

- Organization / Project / Member / API Key；
- Project API Key 消费计入项目；
- Project 可配置预算和消费提醒；
- 官方建议项目成员不要共享 API Key。

3. Kimi API 开放平台相关账单/充值协议
   https://platform.kimi.com/docs/agreement/payment

本项目对 Kimi 汇总账具体字段的主要证据仍是本地 E2 导出文件，而不是从协议中推断字段。

---

## GLM / 智谱

4. 智谱 — 费用问题
   https://docs.bigmodel.cn/cn/faq/fee-issues

关键事实：

- 不同产品存在 Token / 按次等计费方式；
- 费用扣减和资源包扣减是不同机制；
- 平台提供费用账单和汇总账单导出。

5. 智谱 — 数据分析 / 月度账单与费用明细说明
   https://docs.bigmodel.cn/cn/best-practice/case/data-analysis

关键事实：

- 存在月度费用明细；
- 可以分析用量、单价、消费金额等费用字段。

6. 智谱 — 发票问题
   https://docs.bigmodel.cn/cn/faq/invoice-issues

关键事实：

- 开票口径以“实际消耗的现金金额”为重要依据；
- 充值金额本身并不等价于可开票消费金额。

---

## MiMo

7. MiMo — Feature Updates
   https://mimo.mi.com/docs/en-US/updates/feature

关键事实：

- 支持实时查看并导出 Token 消耗、调用次数等 Usage。

8. MiMo — Token Plan
   https://mimo.mi.com/docs/en-US/price/token-plan

关键事实：

- Token Plan 使用 Credit quota；
- cache hit / cache miss / output Token 可以有不同 Credit 转换率；
- Subscription purchase 与 PAYG API 是不同购买路径；
- Token / Credit / payment 不应混为同一计量。

---

## OpenAI API

9. OpenAI Help — Export monthly usage details from API Usage Dashboard
   https://help.openai.com/en/articles/20001072-how-do-i-export-monthly-usage-details-from-the-api-usage-dashboard

关键事实：

- Export 分 `Activity data` 与 `Cost data`；
- Activity 可按 API capability 过滤；
- 可按 project / user / API key / model 等分组；
- Cost export 可用于 detailed cost reporting / invoice reconciliation 类用途。

10. OpenAI API — Organization Usage: Completions
    https://developers.openai.com/api/reference/resources/admin/subresources/organization/subresources/usage/methods/completions/

关键事实：

- input/output/cache tokens；
- request count；
- project/user/api-key/model/batch/service tier 等维度。

11. OpenAI API — Organization Costs
    https://developers.openai.com/api/reference/resources/admin/subresources/organization/subresources/usage/methods/costs/

关键事实：

- monetary amount；
- project / line item / API key group-by；
- Usage 和 Costs 是独立资源。

---

## FOCUS

12. FOCUS Specification — latest 1.4
    https://focus.finops.org/focus-specification/

关键事实：

- FOCUS 1.4 于 2026-06-04 ratified；
- 新增 Invoice Detail 与 Billing Period；
- 提供 Usage-to-Invoice reconciliation 语义。

13. FOCUS — What is FOCUS
    https://focus.finops.org/what-is-focus/

14. FOCUS 1.0 / 1.3 specification
    https://focus.finops.org/focus-specification/v1-0/
    https://focus.finops.org/focus-specification/v1-3/

关键事实：

- Consumed Quantity 与 Pricing Quantity 是不同语义；
- Consumption 与 Pricing 不应混淆。

15. FOCUS FAQ
    https://focus.finops.org/faqs/

关键事实：

- Invoice Detail 与 Cost & Usage 是不同 datasets；
- Invoice reconciliation；
- rounding tolerance 等成熟概念。

---

## FinOps for AI

16. FinOps Foundation — How to Build a Generative AI Cost and Usage Tracker
    https://www.finops.org/wg/how-to-build-a-generative-ai-cost-and-usage-tracker/

关键事实：

- token-level cost tracking；
- centralized vs decentralized；
- cost attribution；
- hub/proxy 是一种现实治理方式；
- 最终成本报告仍需与账单/FinOps 体系连接。

17. FinOps Foundation — Tokenomics: Managing AI Value in SaaS Model Token Costs
    https://www.finops.org/wg/token-economics-saas/

关键事实：

- 先 inventory / visibility；
- API key governance / proxy / attribution；
- 按 Crawl → Walk → Run 逐步增加主动治理。

18. FinOps for AI Overview
    https://www.finops.org/wg/finops-for-ai-overview/

---

## Local Evidence Files

这些文件不作为互联网公开引用，仅作为项目 E2/E3 研究输入。

| Provider | File | SHA-256 |
|---|---|---|
| DeepSeek | `usage_data_2026-07-13_2026-08-11.zip` | `abe8dbb983d40ec0ee2dcecb943c2f6363f89430bed1bfcee7e0697425392d62` |
| Kimi | `moonshot开放平台账单_20250301-20260131_e4c7b13e.xlsx` | `013549cf4359037d0a9b0cbc4285533ec37028c111734a4519bb9c3f99e16c40` |
| GLM | `智谱AI开放平台月度账单2026-03-2026-08_1786455853811.xlsx` | `e1f6f7be6f3064411fe680403983cf613ac2122bc13d65d0306ccc3ea9110b23` |
| MiMo | `usage_data_20260801_20260831_1315186008.xlsx` | `ea37b0db8d1d5914aec31f76e1739107cd9d68e4a069f1b84fac18507ec3dd84` |
| OpenAI Activity | `completions_usage_2026-07-12_2026-08-11.csv` | `1cef32926aefa8c2fe35fdd8ef0af98975052e58a127c28e7450dc2ece756a08` |
| OpenAI Cost | `cost_2026-07-12_2026-08-11.csv` | `1cef32926aefa8c2fe35fdd8ef0af98975052e58a127c28e7450dc2ece756a08` |


---

## Technology Baseline

> 只用于确认当前技术组合的官方支持状态，不属于 AI CostOps 业务事实。

### Spring Boot

19. Spring Boot — System Requirements
    https://docs.spring.io/spring-boot/system-requirements.html

- Spring Boot 4.1.0 支持 Java 17–26；
- Java 21 在支持范围内。

### MyBatis

20. MyBatis Spring Boot Starter
    https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/

- Starter 4.0 支持 Spring Boot 4.0+；
- Java 17+。

### MySQL

21. MySQL 8.4 — Innovation and LTS
    https://dev.mysql.com/doc/refman/8.4/en/mysql-releases.html

- MySQL 8.4 属于 LTS 系列；
- LTS 面向稳定特性集和较长支持周期。

### React

22. React Versions
    https://react.dev/versions

- 当前 React 主线为 19.x。

### Ant Design

23. Ant Design React Introduction
    https://ant.design/docs/react/introduce/

- 官方定位适合 enterprise-class web application。

### Redis

24. Redis — Rate Limiter
    https://redis.io/docs/latest/develop/use-cases/rate-limiter/

- Redis 的原子计数、TTL/Lua 适合限流；
- 这不意味着财务 truth 应存在 Redis。

### Docker Compose

25. Docker Compose Documentation
    https://docs.docker.com/compose/

- 用于多容器 V1 集成交付、healthcheck、volumes、profiles。


### MySQL queue-like locking semantics

26. MySQL 8.4 `SELECT` / `SKIP LOCKED`
    https://dev.mysql.com/doc/refman/8.4/en/select.html

Use only for queue-like ImportAttempt claiming, not as a general consistency shortcut.

### GitHub repository rulesets

27. GitHub — Available rules for rulesets
    https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets

### Redis rate limiting

28. Redis — Rate limiter
    https://redis.io/docs/latest/develop/use-cases/rate-limiter/
