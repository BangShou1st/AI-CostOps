# Backup & Restore Operations (AIC-080)

## Safety boundary（最重要）

所有 restore 操作都 **NON-DESTRUCTIVE** 到普通开发栈：

- **禁止**对普通开发栈执行：`docker compose down -v`、`docker volume prune`、`docker system prune`。
- **禁止**删除或覆盖 main `mysql` / `minio` volume。
- restore 只发生在一个独立 Compose project：`aicostops-restore-drill-<timestamp>`，独立 network / volumes / ports。
- cleanup 只清理 drill 自己创建的资源。
- 备份产物只写入 git-ignored 的本地路径：

```text
.local-backups/mysql/<timestamp>/dump.sql (+ .sha256, backup.json)
.local-backups/evidence/<timestamp>/            (+ backup-manifest.json)
.local-restore-drill/<timestamp>/              (drill 运行目录)
```

## 1. MySQL 逻辑备份

使用运行中 MySQL 容器的 `mysqldump`，输出到 `.local-backups/mysql/<yyyyMMdd-HHmmss>/`，
并生成 SHA-256 sidecar。

```powershell
Set-Location "E:\AI-CostOps"
.\scripts\ops\backup-mysql.ps1 -EnvFile .env -ProjectName ai-costops
```

- 密码通过容器环境变量 `MYSQL_PWD` 传递，**绝不打印**。
- 默认拒绝写到 `.local-backups/mysql` 之外（`-ForceExplicitPath` 可显式覆盖）。

## 2. Evidence 镜像备份

用一次性官方 MinIO Client 容器把配置的 Evidence bucket 镜像到
`.local-backups/evidence/<timestamp>/`：

```powershell
.\scripts\ops\backup-evidence.ps1 -EnvFile .env -ProjectName ai-costops
```

- 只读源 bucket，不触碰普通 Evidence bucket。
- 镜像后逐文件计算 SHA-256 写入 `backup-manifest.json`，供 restore 做字节级校验。

## 3. restore-drill（端到端非破坏性演练）

单条命令完成：synthetic 源数据 → 备份 → 隔离项目恢复 → 验证 → 清理 → PASS marker。

```powershell
.\scripts\ops\restore-drill.ps1 -EnvFile .env -SourceProject ai-costops
# 可选：-DrillFrontendPort <port> 指定独立前端端口；-KeepOnFailure 保留 drill 供排查
```

流程：

```text
1. 源栈只读就绪检查（mysql/redis/minio/backend/frontend healthy + liveness）
2. 通过公共 API 创建 synthetic 数据：DeepSeek import（confirm）+ expense（approve/allocate/post）
   并记录源计数（charges / expenses / ledger postings / ledger entries / periods / evidence）
3. backup-mysql + backup-evidence
4. 启动隔离项目（aicostops-restore-drill-<ts>，独立 network/volumes/ports，复用已构建镜像）
5. restore-mysql（含 SHA-256 校验）+ restore-evidence（逐对象哈希校验）
6. 启动隔离 backend/frontend
7. 验证：login、financial counts、ledger/period state、Evidence 下载内容一致性
8. cleanup 只清理隔离项目
9. 仅在全部断言通过后打印 M9_RESTORE_DRILL_PASS
```

若任一步失败（backup 失败、restore 失败、hash/count 不匹配、login 失败、evidence 缺失、
ledger/period 不匹配、cleanup 关键失败），**不会打印 PASS**，脚本以非零码退出。

## 4. RPO / RTO 定位

本阶段记录的是 **engineering evidence**（实测 backup/restore 耗时），不是生产
SLA/SLO/RTO 承诺：

```text
工程目标（engineering objective）：
  MySQL 备份 < 5 分钟（本地 < 3 秒量级，取决于数据量）
  Evidence 镜像 < 5 分钟
  restore-drill 全流程 < 30 分钟
```

在真实生产部署与压力数据出现前，这些只是工程目标，不是承诺。

## 5. 命令速查

| 操作 | 命令 |
|---|---|
| MySQL 备份 | `.\scripts\ops\backup-mysql.ps1 -EnvFile .env` |
| Evidence 备份 | `.\scripts\ops\backup-evidence.ps1 -EnvFile .env` |
| 端到端演练 | `.\scripts\ops\restore-drill.ps1 -EnvFile .env` |
| MySQL 单独恢复（到隔离项目） | `.\scripts\ops\restore-mysql.ps1 -SourceDump <dump> -ProjectName <project>` |
| Evidence 单独恢复（到隔离项目） | `.\scripts\ops\restore-evidence.ps1 -SourceDir <dir> -ProjectName <project>` |