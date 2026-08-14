import type { MasterDataStatus } from './api/settingsTypes'

/**
 * Presentation-only Simplified-Chinese mapping. These labels are never sent
 * to the backend; API values (status codes, role codes, permission codes)
 * stay English in every payload.
 */

export const STATUS_LABELS: Record<MasterDataStatus, string> = {
  ACTIVE: '启用',
  DISABLED: '已停用',
  ARCHIVED: '已归档',
}

/** Known Role codes; unknown roles fall back to their raw code (fail safe). */
const ROLE_LABELS: Record<string, string> = {
  EMPLOYEE: '员工（EMPLOYEE）',
  PROJECT_OWNER: '项目负责人（PROJECT_OWNER）',
  SYSTEM_ADMIN: '系统管理员（SYSTEM_ADMIN）',
  FINANCE_ADMIN: '财务管理员（FINANCE_ADMIN）',
  FINANCE_REVIEWER: '财务复核员（FINANCE_REVIEWER）',
}

export function statusLabel(status: MasterDataStatus): string {
  return STATUS_LABELS[status] ?? status
}

export function roleLabel(code: string): string {
  return ROLE_LABELS[code] ?? code
}

export function scopeLabel(scopeType: string, scopeId: string): string {
  const typeNames: Record<string, string> = {
    ORG: '组织',
    PROJECT: '项目',
    TEAM: '团队',
    COST_CENTER: '成本中心',
  }
  return `${typeNames[scopeType] ?? scopeType} ${scopeId}`
}

export const SETTINGS_COPY = {
  brand: 'AI CostOps',
  restoreSession: '正在恢复会话…',
  forbiddenTitle: '访问被拒绝',
  forbiddenDetail: '您没有查看此页面的权限。如您认为这是误判，请联系管理员。',
  signOut: '退出登录',
  menu: '菜单',
  collapseSidebar: '收起侧边栏',
  expandSidebar: '展开侧边栏',
} as const

export const USER_STATUS_ACTIONS = {
  disable: '停用',
  enable: '启用',
} as const
