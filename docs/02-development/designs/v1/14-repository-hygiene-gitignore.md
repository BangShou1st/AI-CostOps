# 14. Git 仓库卫生与忽略规则

目标：

> 仓库可以复现构建，但不包含个人机器状态、Secret、Build Output、Runtime Data、真实 Provider 私有数据。

## 1. 应提交

### Root

```text
README.md
CONTRIBUTING.md
PROJECT_CONTEXT.md
.editorconfig
.gitattributes
.gitignore
.env.example
.github/**
docs/**
scripts/**   # 仅 Review 后的通用脚本
```

### Backend

```text
backend/pom.xml
backend/mvnw
backend/mvnw.cmd
backend/.mvn/**
backend/Dockerfile
backend/src/main/**
backend/src/test/**
```

Maven Wrapper 要 Commit。

### Frontend

```text
package.json
package-lock.json
src/**
public/**
index.html
tsconfig*.json
vite.config.*
eslint.config.*
Dockerfile
```

`package-lock.json` 必须 Commit，保证两个人和 CI 依赖解析一致。

### Docker / DB

```text
compose.yaml
compose.dev.yaml
deploy/nginx/**
db/migration/**
```

所有 Flyway Migration 必须 Commit。

## 2. 永远不能提交 Secret

```text
.env
.env.*
!.env.example

*.pem
*.key
*.p12
*.pfx

secrets/
credentials/
```

禁止：

```text
JWT Signing Key
真实 MySQL Password
Redis Password
MinIO Secret
Provider API Key
SMTP Credential
GitHub Token
```

如果 Secret 已经 Push：

> 后续删除文件不够，必须 Rotate / Revoke；必要时清理 Git History。

## 3. Build Output

不提交：

```text
backend/target/
frontend/node_modules/
frontend/dist/
coverage/
```

## 4. Runtime Data

不提交：

```text
data/
mysql-data/
redis-data/
minio-data/
uploads/
tmp/
temp/
```

优先使用 Docker Named Volume。

## 5. Log / Test Artifact

不提交 Raw Runtime Log：

```text
*.log
logs/
```

CI Raw Artifact 一般作为 GitHub Actions Artifact，不进入源码：

```text
Surefire XML
Coverage Raw
Heap Dump
Profiler Output
```

可以提交经过整理、可复现的人类报告：

```text
docs/quality/reports/benchmark-*.md
```

前提是真实实测。

## 6. IDE / OS

默认 Ignore：

```text
.idea/
*.iml
.vscode/
.DS_Store
Thumbs.db
```

如果未来两个人都需要共享极少量 VSCode Recommended Extension，可以单独讨论后白名单 Commit。

## 7. Provider 原始文件

禁止把真实研究原件直接上传 Public Repo。

尤其是包含：

```text
User/Org ID
API Key
Billing Identity
Invoice Info
Private Usage
Personal Data
```

的 ZIP/XLSX/CSV/PDF。

正确做法：

```text
真实原件
→ Repo 外保留
→ Sanitized Fixture
→ Git
```

Sanitized Fixture 保留 Schema，但用 Fake/Synthetic Data。

## 8. DB Dump

不提交：

```text
*.sql Dump
*.dump
*.bak
```

Schema 来源：

```text
Flyway
```

Demo Data 来源：

```text
Seed
Fixture
Explicit Bootstrap
```

## 9. Dependency Cache

不提交：

```text
.m2/
.npm/
.pnpm-store/
.yarn/
.gradle/
```

## 10. Tool Cache

例如：

```text
.cache/
.sonar/
.trivy/
```

Config 可以 Commit，Generated Cache 不 Commit。

## 11. 推荐 `.gitignore`

```gitignore
# Secrets / local env
.env
.env.*
!.env.example
*.pem
*.key
*.p12
*.pfx
secrets/
credentials/

# Java / Maven
backend/target/
*.class
*.log
hs_err_pid*
replay_pid*

# Node / React
frontend/node_modules/
frontend/dist/
frontend/coverage/
frontend/.vite/
npm-debug.log*
yarn-debug.log*
yarn-error.log*

# Tests / profiling
coverage/
test-results/
playwright-report/
*.hprof
*.heapdump

# Runtime data
data/
mysql-data/
redis-data/
minio-data/
uploads/
tmp/
temp/

# IDE
.idea/
*.iml
.vscode/
*.code-workspace

# OS
.DS_Store
Thumbs.db

# Tool caches
.cache/
.sonar/
.trivy/
```

真正 Bootstrap 时按实际生成文件再 Review 一次。

## 12. `.gitattributes`

建议：

```gitattributes
* text=auto

*.sh text eol=lf
*.yml text eol=lf
*.yaml text eol=lf
*.xml text eol=lf
*.java text eol=lf
*.ts text eol=lf
*.tsx text eol=lf

*.png binary
*.jpg binary
*.jpeg binary
*.xlsx binary
*.zip binary
*.pdf binary
```

## 13. `.editorconfig`

建议：

```ini
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
trim_trailing_whitespace = true

[*.{java,xml,yml,yaml,json,ts,tsx,js,jsx,css,md}]
indent_style = space
indent_size = 2

[*.java]
indent_size = 4

[*.md]
trim_trailing_whitespace = false
```

## 14. Push 前人工检查

至少执行：

```bash
git status
git diff --staged
```

确认：

```text
没有 .env
没有 Secret
没有真实 Provider Export
没有 node_modules / target
没有 Local DB
没有 IDE Folder
没有异常大 Binary
```

## 15. Large File

默认不引入 Git LFS。

先问：

```text
这个文件真的应该进 Git 吗？
能否生成？
能否用小 Fixture？
```

只有确实必要再评估 LFS。
