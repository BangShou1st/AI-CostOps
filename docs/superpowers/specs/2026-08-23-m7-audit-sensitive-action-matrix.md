# M7 Sensitive Action Coverage Matrix（AIC-065）

> 生成于 PR3 `feat/m7-audit-query`。全部行基于代码调查（`AuditService.append` 全部 45 个调用点、
> 各模块 `Audit*Adapter`、集成测试断言），非推测。核对基准：`docs/02-development/detailed-design/06-permission-matrix.md` §11「必须 Audit 的动作」。
>
> 审计写入统一经过 `com.aicostops.audit.application.AuditService`：metadata key 含
> `password/token/secret/jwt/apikey/api_key`（大小写不敏感）即抛 `IllegalArgumentException` 拒绝写入，
> 因此所有 producer 天然 secret-safe；多个集成测试额外断言 metadata 不含敏感片段。
> 查询侧：`GET /api/v1/audit-events`（AUDIT_READ @ ORG，本 PR 新增）。

## 覆盖矩阵

| Domain | Sensitive Action | Endpoint / Command | Expected Audit Event Type | Current Audit Producer / Adapter | Actor | Subject Type | Org Scope | Metadata | Secret-safe | Coverage Status | Evidence / Test | Gap / Follow-up |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| IAM | 登录成功/失败 | `POST /auth/login` | `LOGIN_SUCCESS` / `LOGIN_FAILED` | `LoginService` | 用户 / 未认证者 | `USER` | ORG（未知身份的失败为 `org_id=NULL`，设计使然） | `result` | ✅ | COVERED | `LoginServiceIntegrationTest`（LOGIN_SUCCESS 计数+metadata）；`AuditServiceTest`（secret key 拒绝） | — |
| IAM | 登出 / 会话吊销 / 改密 | `POST /auth/logout(-all)`、refresh replay、`POST /auth/password/reset` | `LOGOUT` / `SESSION_REVOKED` / `PASSWORD_CHANGED` | `LogoutService`、`RefreshService`、`PasswordResetService` | 用户 | `USER` | `org_id=NULL`（跨组织事件） | `scope` / `reason` / `method` | ✅ | PARTIAL | `RefreshAndLogoutApiIntegrationTest`（负向 verify；无正向 DB 断言） | 事件不可被 org-scoped 审计查询命中；建议回填组织或引入 platform 级查询 |
| IAM | 邀请创建 / 接受 | `POST /invitations`、`POST /invitations/{token}/accept` | `INVITATION_CREATED` / `INVITATION_ACCEPTED` | `AdminInvitationService`、`InvitationAcceptanceService` | 管理员 / 受邀人 | `INVITATION` | ORG | `email,initialRoleCode,expiresAt` / `invitationId` | ✅（raw token 不入 audit） | COVERED | `AdminInvitationServiceIntegrationTest`、`InvitationAcceptanceServiceIntegrationTest` | — |
| IAM | 角色授予 / 撤销（§11） | `POST/DELETE /role-assignments` | `ROLE_ASSIGNED` / `ROLE_REVOKED` | `RoleAssignmentService` | 管理员（fresh context） | `ROLE_ASSIGNMENT` | ORG | `targetMemberId,roleCode,scopeType,scopeId` | ✅（断言无 secret 片段） | COVERED | `IamMutationApiIntegrationTest.assertRoleAudit` | — |
| IAM | 用户禁用 / 启用（§11） | `PATCH /users/{id}/status` | `USER_DISABLED` / `USER_ENABLED` | `UserAdminService` | 管理员 | `USER` | ORG | `previousStatus,newStatus,targetMemberId` | ✅ | COVERED | `IamMutationApiIntegrationTest.assertUserAudit` | — |
| Organization | 项目/团队/成本中心成员变更 | `POST/DELETE /projects/{id}/members`、`/teams/{id}/members` | `MEMBERSHIP_CHANGED` | `ProjectMembershipService`、`TeamMembershipService` | 管理员 | `PROJECT_MEMBER` / `TEAM_MEMBER` | ORG | `parentType,parentId,memberId,previousStatus,newStatus` | ✅ | COVERED | `TeamMembershipApiIntegrationTest`（metadata JSON 逐键断言）、`ProjectMembershipApiIntegrationTest` | — |
| Organization | 项目/团队/成本中心实体 CRUD | `POST/PATCH /projects|teams|cost-centers` | —（无 producer） | — | 管理员 | — | — | — | — | PARTIAL | — | 主数据创建/更新/归档未审计（不在 §11 清单）；非阻塞 follow-up |
| Organization | Provider account 创建/更新/归档 | `POST/PATCH /provider-accounts` | —（无 producer） | — | 财务管理员 | — | — | — | — | **GAP** | — | `external_account_ref` 属凭证邻近字段，变更应审计；不在 §11 清单 → 非阻塞 follow-up（建议 `AuditOrganizationAdapter`） |
| Ingestion | Import confirm / retry / cancel（§11） | `POST /imports/{id}/confirm|retry|cancel` | `IMPORT_CONFIRMED` / `IMPORT_RETRIED` / `IMPORT_CANCELED` | `AuditImportWorkflowAdapter` | 财务 | `IMPORT_BATCH` | ORG | 仅 id 与状态名（payload/文件名/凭证显式排除） | ✅ | COVERED | `ImportConfirmIntegrationTest`、`ImportWorkflowRetryIntegrationTest`、`ImportWorkflowCancelIntegrationTest`、`ImportWorkflowAuditRollbackIntegrationTest` | — |
| Ingestion | Import 上传 / 运行（非 confirm 路径） | `POST /provider-imports`、worker 执行 | —（设计上不审计） | — | — | — | — | — | — | NOT_APPLICABLE | — | §11 仅要求 confirm/retry/cancel；高频低敏 |
| Expense | 报销创建/编辑/提交/取消 | `POST/PUT /expenses`、`/submit`、`/cancel` | `EXPENSE_CREATED/EDITED/SUBMITTED/CANCELED` | `AuditExpenseAdapter` | 员工 | `EXPENSE_CLAIM` | ORG | `currency,version,actionType` | ✅ | COVERED | `ExpenseLifecycleIntegrationTest`、`ExpenseIdempotencyIntegrationTest` | — |
| Expense | 审批 approve/reject/needs-info（§11） | `POST /expenses/{id}/approve|reject|request-info` | `EXPENSE_REVIEWED` | `AuditExpenseAdapter` | 财务 | `EXPENSE_CLAIM` | ORG | `actionType,version,comment?` | ✅（comment 为审批意见，非凭证） | COVERED | `ExpenseLifecycleIntegrationTest`（计数=2 断言） | — |
| Expense | 报销过账（§11 Ledger Post） | `POST /expenses/{id}/post` | `LEDGER_EXPENSE_POSTED` | `AuditLedgerAdapter` | 财务 | `LEDGER_POSTING` | ORG | `expenseClaimId,allocationDecisionId,entryCount,currency` | ✅ | COVERED | `ExpensePostingIntegrationTest`/`ExpensePostingApiIntegrationTest` | — |
| Expense | 证据挂载 | `POST /expenses/{id}/evidence` | `EXPENSE_EVIDENCE_ATTACHED` | `AuditExpenseAdapter` | 员工 | `EXPENSE_CLAIM` | ORG | `evidenceId,version` | ✅ | COVERED | `ExpenseEvidenceIntegrationTest` | — |
| Budget | 预算创建 / 总额变更（§11） | `POST/PUT /budgets` | `BUDGET_CREATED` / `BUDGET_TOTAL_CHANGED` | `AuditBudgetAdapter` | 预算管理员 | `BUDGET` | ORG | `currency,scopeType,scopeId,totalAmount,version` | ✅（`createAuditsMinimalSecretFreeMetadata`） | COVERED | `BudgetApiIntegrationTest` | — |
| Budget | Commitment 请求/批准/拒绝/取消/释放（§11） | `POST /commitments/{id}/approve|reject|cancel|release` 等 | `COMMITMENT_REQUESTED/ACTIVATED/REJECTED/CANCELED/RELEASED` | `AuditCommitmentAdapter` | 申请人/审批人 | `BUDGET_COMMITMENT` | ORG | `budgetId,approvalCaseId,fromStatus,toStatus,amounts` | ✅ | COVERED | `CommitmentRequest/Activation/Release/RejectCancel/Concurrency/RollbackIntegrationTest` | — |
| Budget | Commitment 消化（系统） | ledger 消费路径 | `COMMITMENT_CONSUMED` | `AuditCommitmentAdapter` | 系统（actor 可空） | `BUDGET_COMMITMENT` | ORG | `budgetId,ledgerEntryId,fromStatus,toStatus,consumedAmount` | ✅ | COVERED | `CommitmentConsumeIntegrationTest` | — |
| Cost review | 重复保留 / 排除（§11） | `POST /duplicate-candidates/{id}/keep|exclude` | `DUPLICATE_CANDIDATE_KEPT_CLEAN` / `_EXCLUDED` | `AuditDuplicateReviewAdapter` | 审查人 | `DUPLICATE_CANDIDATE` / `CHARGE_FACT` | ORG | 仅 id/枚举/计数（provider 值与 raw payload 显式排除） | ✅ | COVERED | `DuplicateReviewCommandIntegrationTest`、`DuplicateCandidateApiIntegrationTest` | — |
| Allocation | 分摊决策确认（§11） | `POST /allocation-decisions/{id}/confirm` | `ALLOCATION_DECISION_CONFIRMED` | `AuditAllocationAdapter` | 分摊操作员 | `CHARGE_FACT` / `EXPENSE_CLAIM` | ORG | `allocationDecisionId,decisionSource,allocationRuleId?,lineCount,currency` | ✅ | COVERED | `AllocationCommandIntegrationTest`、`ExpenseAllocationConfirmIntegrationTest`（subject_type 断言） | — |
| Allocation | 规则版本发布 / 归档 | `POST /allocation-rules/{key}/versions`、`/{id}/archive` | —（无 producer） | — | 分摊管理员 | — | — | — | — | **GAP** | — | `ALLOCATION_RULE_MANAGE` 变更未审计；不在 §11 清单 → 非阻塞 follow-up |
| Ledger | 费用过账（§11） | `POST /costs/charges/{id}/post` | `LEDGER_CHARGE_POSTED` | `AuditLedgerAdapter` | 财务 | `LEDGER_POSTING` | ORG | `chargeFactId,allocationDecisionId,entryCount,currency` | ✅ | COVERED | `ProviderChargePostingIntegrationTest`（含幂等不重复审计断言）、`ProviderPostingRollbackIntegrationTest` | — |
| Ledger | 冲正（§11 Correction） | `POST /ledger/corrections` | `LEDGER_CORRECTION_POSTED` | `AuditLedgerAdapter` | 财务 | `LEDGER_POSTING` | ORG | `correctionGroupId,targetEntryId,mode,entryCount,currency` | ✅ | COVERED | `LedgerCorrectionIntegrationTest` | — |
| Reconciliation | 对账运行完成/失败 | `POST /reconciliation-runs` | `RECONCILIATION_RUN_COMPLETED` / `_FAILED` | `AuditReconciliationAdapter` | 财务 | `RECONCILIATION_RUN` | ORG | `billingPeriodId,caseCount,algorithmVersion` / `errorCode` | ✅ | COVERED | producer + M6 套件驱动全流程（无逐事件直接断言） | 建议补逐事件断言（证据强度 follow-up） |
| Reconciliation | Case investigate/return-open/resolve（§11） | `POST /reconciliation-cases/{id}/...` | `RECONCILIATION_CASE_{action}` | `AuditReconciliationAdapter` | 财务 | `RECONCILIATION_CASE` | ORG | `action,reasonCode?` | ✅ | COVERED | 同上 | 同上 |
| Reconciliation | Period close 启动/阻塞/失败/完成（§11） | `POST /billing-periods/{id}/close` | `PERIOD_CLOSE_STARTED/BLOCKED/FAILED/CLOSED` | `AuditReconciliationAdapter` | 财务 | `PERIOD_CLOSE_RUN` / `BILLING_PERIOD` | ORG | `billingPeriodId,closeGeneration,attemptNo/failedCheckCount/closeRunId` | ✅（`reasonNote` 为人工说明） | COVERED | E2E 主链路驱动 close；`PeriodReopenIntegrationTest` 直接断言 audit metadata | — |
| Reconciliation | Period reopen（§11） | `POST /billing-periods/{id}/reopen` | `PERIOD_REOPENED` | `AuditReconciliationAdapter` | 财务 | `BILLING_PERIOD` | ORG | `oldGeneration,newGeneration,reasonCode,reasonNote` | ✅ | COVERED | `PeriodReopenIntegrationTest`（metadata_json 直接断言） | — |
| Auth（平台） | 密码找回请求 | `POST /auth/password/forgot` | —（设计上不审计） | — | 未认证 | — | — | — | — | NOT_APPLICABLE | — | 防账号枚举：无身份前不写审计（设计决定） |
| Auth（平台） | Refresh 正常轮换 | `POST /auth/refresh` | —（设计上不审计；仅 replay 审计） | — | 用户 | — | — | — | — | NOT_APPLICABLE | — | 高频低敏；安全相关 replay 已由 `SESSION_REVOKED(reason=REFRESH_REPLAY)` 覆盖 |
| Audit | 审计查询本身（本 PR） | `GET /api/v1/audit-events` | —（只读，不审计） | — | 审计读者 | — | — | — | — | NOT_APPLICABLE | `AuditQueryApiIntegrationTest`（权限/隔离/过滤/分页 11 用例） | 读操作不产生审计（避免自引用）；如需查询审计可作 follow-up |

## 汇总

| 指标 | 数量 |
|---|---|
| 覆盖总数（矩阵行） | 27 |
| COVERED | 19 |
| PARTIAL | 2 |
| GAP | 2 |
| NOT_APPLICABLE | 4 |

### M7 blocking gap

**无。** `06-permission-matrix.md` §11「必须 Audit 的动作」全部 12 项（User Disable、Role Assign/Revoke、
Import Confirm、Duplicate Exclude、Allocation Confirm、Expense Approve/Reject、Budget Total Change、
Commitment Activate/Release、Ledger Post、Correction、Reconciliation Resolve、Period Close/Reopen）
均有 producer、ORG 隔离、secret-safe，且绝大多数有集成测试直接断言。AIC-065 验收不被阻止。

### Non-blocking follow-up

1. **GAP**：`PROVIDER_ACCOUNT_MANAGE`（创建/更新/归档）无审计 producer——`external_account_ref` 为凭证邻近字段，建议补 `AuditOrganizationAdapter`（需配套测试）。
2. **GAP**：`ALLOCATION_RULE_MANAGE`（版本发布/归档）无审计 producer。
3. **PARTIAL**：`LOGOUT` / `SESSION_REVOKED` / `PASSWORD_CHANGED` 以 `org_id=NULL` 写入，本 PR 的 org-scoped 查询查不到；建议回填组织或引入 platform 级审计视图。
4. **PARTIAL**：项目/团队/成本中心实体 CRUD 未审计（成员变更已覆盖）。
5. **证据强度**：reconciliation run/case/close 多数事件仅有 producer + 流程级测试，无逐事件 audit 断言；`LOGOUT`/`SESSION_REVOKED` 仅有负向 verify。
