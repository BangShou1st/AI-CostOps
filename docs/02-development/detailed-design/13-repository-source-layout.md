# 13. 仓库与源码目录设计

## 1. Monorepo

```text
AI-CostOps/
├── .github/
│   ├── ISSUE_TEMPLATE/
│   ├── workflows/
│   ├── CODEOWNERS
│   └── pull_request_template.md
│
├── backend/
│   ├── .mvn/
│   ├── mvnw
│   ├── mvnw.cmd
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/
│       ├── main/
│       │   ├── java/com/aicostops/
│       │   └── resources/
│       └── test/
│
├── frontend/
│   ├── src/
│   ├── public/
│   ├── index.html
│   ├── package.json
│   ├── package-lock.json
│   ├── tsconfig.json
│   ├── vite.config.ts
│   ├── eslint.config.js
│   └── Dockerfile
│
├── deploy/
│   └── nginx/
├── docs/
├── scripts/
├── .editorconfig
├── .gitattributes
├── .gitignore
├── .env.example
├── compose.yaml
├── compose.dev.yaml
├── CONTRIBUTING.md
├── PROJECT_CONTEXT.md
└── README.md
```

没有真实文件的空目录不提前创建。

## 2. Backend

业务模块：

```text
com.aicostops/
├── shared/
├── iam/
├── organization/
├── evidence/
├── ingestion/
├── cost/
├── attribution/
├── expense/
├── budget/
├── ledger/
├── reconciliation/
├── period/
├── audit/
└── reporting/
```

模块内部按需要：

```text
api/
application/
domain/
infrastructure/
```

例如 Ledger：

```text
ledger/
├── api/
│   ├── LedgerQueryController.java
│   └── dto/
├── application/
│   ├── PostChargeUseCase.java
│   └── port/
├── domain/
│   ├── LedgerPosting.java
│   ├── LedgerEntry.java
│   └── LedgerRepository.java
└── infrastructure/
    └── persistence/
        ├── LedgerMyBatisRepository.java
        ├── LedgerMapper.java
        └── model/
```

不要整个系统采用：

```text
controller/
service/
mapper/
entity/
```

## 3. DTO / Domain / Persistence Model

重要财务模块不要一个类同时充当：

```text
HTTP DTO
Domain Model
MyBatis Row
```

例如：

```text
api.dto.LedgerEntryResponse
domain.LedgerEntry
infrastructure.persistence.model.LedgerEntryRow
```

简单 Master Data 可以适当简化，但 Controller 不直接暴露数据库 Row。

## 4. MyBatis

Java Mapper：

```text
<module>/infrastructure/persistence/
```

XML：

```text
backend/src/main/resources/mapper/<module>/
```

只有真的需要 XML 时才建。

Critical SQL 必须显式：

```text
Budget Conditional UPDATE
Import Claim
Ledger Query/Posting
Close Query
Reconciliation Aggregate
```

## 5. Backend Test

Test Package 镜像业务模块：

```text
backend/src/test/java/com/aicostops/
```

Naming：

```text
*Test
*IntegrationTest
*ArchitectureTest
```

例如：

```text
BudgetCommitmentConcurrencyIntegrationTest
LedgerPostingIdempotencyIntegrationTest
ImportAttemptClaimIntegrationTest
ModuleDependencyArchitectureTest
```

MySQL-specific Test 使用 Testcontainers，不使用 H2 替代。

## 6. Fixture

```text
backend/src/test/resources/fixtures/
├── provider/
│   ├── deepseek/
│   ├── kimi/
│   ├── glm/
│   ├── mimo/
│   └── openai/
└── synthetic/
```

只提交：

```text
REAL_SCHEMA_SANITIZED
OFFICIAL_SCHEMA_SYNTHETIC
SYNTHETIC_ENTERPRISE
```

原始个人账户文件不进仓库。

## 7. Frontend

```text
frontend/src/
├── app/
├── api/
├── features/
├── components/
└── shared/
```

每个 Feature 按真实需要：

```text
features/ledger/
├── api/
├── components/
├── hooks/
├── pages/
└── types/
```

不要强制每个 Feature 都建所有空目录。

## 8. API Client

公共基础：

```text
src/api/client.ts
src/api/auth-interceptor.ts
src/api/problem.ts
src/api/pagination.ts
```

业务 API：

```text
features/budgets/api/
features/ledger/api/
```

禁止一个 4000 行全局 `api.ts`。

## 9. Route

```text
app/router/
├── index.tsx
├── publicRoutes.tsx
└── protectedRoutes.tsx
```

Feature Page 留在各 Feature。

## 10. Docker

```text
backend/Dockerfile
frontend/Dockerfile
compose.yaml
deploy/nginx/default.conf
```

Build Output 不进入 Git。

## 11. scripts/

只放两个人都会使用、经过 Review 的脚本。

例如：

```text
scripts/dev/reset-local-data.sh
scripts/test/compose-smoke.sh
```

禁止提交个人临时脚本、Hard-coded Password。

## 12. Root Docs

README 最终包含：

```text
项目是什么
架构图
技术栈
Quick Start
Docker
开发/测试命令
Docs Link
Contributors
真实性边界
```

详细设计留在 `docs/`。

## 13. CONTRIBUTING

简洁说明：

```text
Branch
Commit
Issue → PR
Review
Test
Migration
AI Coding Ownership
```

引用 Detailed Git Governance，不复制全部内容。

## 14. Empty Directory

Git 不跟踪空目录。

不需要到处 `.gitkeep`。

Runtime Data Directory 通常根本不进入 Git。
