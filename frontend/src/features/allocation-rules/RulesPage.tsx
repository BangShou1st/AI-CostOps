import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Form, Input, InputNumber, Modal, Select, Space, Table, Tag, Typography } from 'antd'
import { useState } from 'react'
import { problemDetail as presentProblemDetail, problemSummary, toProblemDetail, type ProblemDetail } from '../../api/problem'
import { READABLE_SELECT_PROPS, readableOption } from '../../lib/selectPresentation'
import { useAuth } from '../auth/AuthSessionProvider'
import { hasPermission } from '../settings/permissions'
import { settingsApi } from '../settings/api/settingsApi'
import { ruleKeys } from './api/ruleKeys'
import {
  rulesApi,
  type AllocationRule,
  type AllocationRuleMatchType,
  type RuleVersionInput,
} from './api/rulesApi'

const PAGE_SIZE = 50

const MATCH_TYPE_LABELS: Record<AllocationRuleMatchType, string> = {
  PROVIDER_API_KEY: 'API Key',
  PROVIDER_PROJECT: '项目',
  PROVIDER_USER: '用户',
}

export function RulesPage() {
  const auth = useAuth()
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [problem, setProblem] = useState<ProblemDetail | null>(null)
  const [editorOpen, setEditorOpen] = useState(false)
  const [form] = Form.useForm<RuleVersionFormValues>()

  const canManage = hasPermission(auth.user?.permissions, 'ALLOCATION_RULE_MANAGE')

  const list = useQuery({
    queryKey: ruleKeys.list({ page, size: PAGE_SIZE }),
    queryFn: () => rulesApi.listRules({ page, size: PAGE_SIZE }),
  })

  // Optional account constraint; when the reader lacks PROVIDER_ACCOUNT_READ
  // the dropdown simply disappears and the rule stays unconstrained (null).
  const providerAccounts = useQuery({
    queryKey: ['provider-accounts', 'list', 'ACTIVE'],
    queryFn: () => settingsApi.listProviderAccounts(0, 200, 'ACTIVE'),
    retry: false,
  })
  const accountOptions = (providerAccounts.data?.items ?? []).map((account) => readableOption(
    account.id,
    `${account.displayName}（${account.providerCode}）`,
  ))

  const refresh = () => {
    setProblem(null)
    void queryClient.invalidateQueries({ queryKey: ruleKeys.lists() })
  }

  const createVersion = useMutation({
    mutationFn: (input: { ruleKey: string; definition: RuleVersionInput }) =>
      rulesApi.createVersion(input.ruleKey, input.definition, crypto.randomUUID()),
    onSuccess: () => {
      form.resetFields()
      setEditorOpen(false)
      refresh()
    },
    onError: (error: unknown) => setProblem(toProblemDetail(error)),
  })

  const archive = useMutation({
    mutationFn: (ruleId: string) => rulesApi.archive(ruleId, crypto.randomUUID()),
    onSuccess: refresh,
    onError: (error: unknown) => setProblem(toProblemDetail(error)),
  })

  if (!canManage) {
    return <main className="settings-page"><h1>分摊规则</h1><Alert type="warning" showIcon title="缺少 ALLOCATION_RULE_MANAGE 权限" /></main>
  }

  const listProblem = list.error ? toProblemDetail(list.error) : null

  return (
    <main className="settings-page">
      <header className="page-header">
        <h1>分摊规则</h1>
        <Button type="primary" onClick={() => setEditorOpen(true)}>创建新版本</Button>
      </header>

      {problem && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          title={problemSummary(problem)}
          description={presentProblemDetail(problem)}
        />
      )}

      {listProblem && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          title="无法加载分摊规则"
          description={(
            <>
              <div>{problemSummary(listProblem)}</div>
              {presentProblemDetail(listProblem) && <div>{presentProblemDetail(listProblem)}</div>}
            </>
          )}
        />
      )}

      {!listProblem && (
      <Table<AllocationRule>
        rowKey="id"
        loading={list.isLoading}
        dataSource={list.data?.items ?? []}
        pagination={{
          current: page + 1,
          pageSize: PAGE_SIZE,
          total: list.data?.totalElements ?? 0,
          showSizeChanger: false,
          onChange: (nextPage) => setPage(nextPage - 1),
        }}
        columns={[
          { title: '规则键', dataIndex: 'ruleKey', width: 130 },
          { title: '版本', dataIndex: 'version', width: 70 },
          { title: '名称', dataIndex: 'name', ellipsis: true },
          { title: '供应商', dataIndex: 'providerCode', width: 100 },
          {
            title: '匹配类型',
            dataIndex: 'matchHintType',
            width: 100,
            render: (type: AllocationRuleMatchType) => MATCH_TYPE_LABELS[type],
          },
          { title: '匹配值', dataIndex: 'matchValue', ellipsis: true },
          { title: '优先级', dataIndex: 'priority', width: 80 },
          {
            title: '目标',
            width: 130,
            render: (_, rule) =>
              rule.targetProjectId
                ? `项目 ${rule.targetProjectId}`
                : rule.targetCostCenterId
                  ? `成本中心 ${rule.targetCostCenterId}`
                  : `团队 ${rule.targetTeamId}`,
          },
          {
            title: '生效区间',
            width: 210,
            render: (_, rule) => `${rule.effectiveFrom.slice(0, 10)} 至 ${rule.effectiveTo?.slice(0, 10) ?? '∞'}`,
          },
          {
            title: '状态',
            dataIndex: 'status',
            width: 90,
            render: (status: string) => (
              <Tag color={status === 'ACTIVE' ? 'green' : 'default'}>{status}</Tag>
            ),
          },
          {
            title: '操作',
            width: 100,
            render: (_, rule) =>
              rule.status === 'ACTIVE' ? (
                <Button size="small" danger loading={archive.isPending} onClick={() => archive.mutate(rule.id)}>
                  归档
                </Button>
              ) : null,
          },
        ]}
      />
      )}

      <Modal
        title="创建规则新版本"
        open={editorOpen}
        okText="创建"
        cancelText="取消"
        confirmLoading={createVersion.isPending}
        onCancel={() => setEditorOpen(false)}
        onOk={() => form.submit()}
      >
        <Typography.Paragraph type="secondary">
          版本号由服务端按规则键自动分配；新定义将作为不可变新版本追加。
        </Typography.Paragraph>
        <Form<RuleVersionFormValues>
          form={form}
          layout="vertical"
          onFinish={(values) => {
            const targetType = values.targetType ?? 'project'
            const targetId = values.targetId
            createVersion.mutate({
              ruleKey: values.ruleKey.trim(),
              definition: {
                name: values.name.trim(),
                providerCode: values.providerCode.trim(),
                providerAccountId: values.providerAccountId ?? null,
                matchHintType: values.matchHintType,
                // matchValue keeps the exact user input: BINARY exact-match
                // semantics on the backend would be broken by any trimming.
                matchValue: values.matchValue,
                priority: values.priority,
                targetProjectId: targetType === 'project' ? targetId : null,
                targetCostCenterId: targetType === 'costCenter' ? targetId : null,
                targetTeamId: targetType === 'team' ? targetId : null,
                effectiveFrom: values.effectiveFrom,
                effectiveTo: values.effectiveTo || null,
              },
            })
          }}
        >
          <Form.Item name="ruleKey" label="规则键" rules={[{ required: true, message: '规则键必填' }]}>
            <Input maxLength={100} />
          </Form.Item>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '名称必填' }]}>
            <Input maxLength={200} />
          </Form.Item>
          <Space style={{ display: 'flex' }} align="start">
            <Form.Item name="providerCode" label="供应商代码" rules={[{ required: true, message: '供应商代码必填' }]}>
              <Input maxLength={100} />
            </Form.Item>
            <Form.Item name="matchHintType" label="匹配类型" initialValue="PROVIDER_PROJECT" rules={[{ required: true }]}>
              <Select
                {...READABLE_SELECT_PROPS}
                style={{ width: '100%', minWidth: 160 }}
                options={[
                  readableOption('PROVIDER_API_KEY', 'API Key'),
                  readableOption('PROVIDER_PROJECT', '项目'),
                  readableOption('PROVIDER_USER', '用户'),
                ]}
              />
            </Form.Item>
          </Space>
          <Form.Item name="matchValue" label="匹配值" rules={[{ required: true, message: '匹配值必填' }]}>
            <Input maxLength={500} />
          </Form.Item>
          {accountOptions.length > 0 && (
            <Form.Item
              name="providerAccountId"
              label="供应商账号（可选）"
              extra="留空表示该规则适用于所有账号。"
            >
              <Select
                {...READABLE_SELECT_PROPS}
                allowClear
                style={{ width: '100%' }}
                placeholder="全部账号"
                options={accountOptions}
              />
            </Form.Item>
          )}
          <Space style={{ display: 'flex' }} align="start">
            <Form.Item name="priority" label="优先级" initialValue={100} rules={[{ required: true, message: '优先级必填' }]}>
              <InputNumber min={1} max={9999} />
            </Form.Item>
            <Form.Item name="targetType" label="目标类型" initialValue="project">
              <Select
                {...READABLE_SELECT_PROPS}
                style={{ width: '100%', minWidth: 120 }}
                options={[
                  readableOption('project', '项目'),
                  readableOption('costCenter', '成本中心'),
                  readableOption('team', '团队'),
                ]}
              />
            </Form.Item>
            <Form.Item name="targetId" label="目标 ID" rules={[{ required: true, message: '目标 ID 必填' }]}>
              <Input />
            </Form.Item>
          </Space>
          <Space style={{ display: 'flex' }} align="start">
            <Form.Item name="effectiveFrom" label="生效时间" rules={[{ required: true, message: '生效时间必填' }]}>
              <Input placeholder="YYYY-MM-DDTHH:mm:ssZ" />
            </Form.Item>
            <Form.Item name="effectiveTo" label="失效时间（可选）">
              <Input placeholder="YYYY-MM-DDTHH:mm:ssZ" />
            </Form.Item>
          </Space>
        </Form>
      </Modal>
    </main>
  )
}

interface RuleVersionFormValues {
  ruleKey: string
  name: string
  providerCode: string
  providerAccountId?: string
  matchHintType: AllocationRuleMatchType
  matchValue: string
  priority: number
  targetType: 'project' | 'costCenter' | 'team'
  targetId: string
  effectiveFrom: string
  effectiveTo?: string
}
