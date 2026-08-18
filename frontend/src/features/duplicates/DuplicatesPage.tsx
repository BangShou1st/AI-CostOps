import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Space, Table, Tag, Typography } from 'antd'
import { useState } from 'react'
import { toProblemDetail, type ProblemDetail } from '../../api/problem'
import { useAuth } from '../auth/AuthSessionProvider'
import { hasPermission } from '../settings/permissions'
import { duplicateKeys } from './api/duplicateKeys'
import { duplicatesApi, type DuplicateCandidate } from './api/duplicatesApi'
import { costKeys } from '../costs/api/costKeys'
import { allocationKeys } from '../allocation/api/allocationKeys'

const PAGE_SIZE = 50

export function DuplicatesPage() {
  const auth = useAuth()
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [problem, setProblem] = useState<ProblemDetail | null>(null)
  const [pendingCandidate, setPendingCandidate] = useState<string | null>(null)
  const [pendingExcludeSide, setPendingExcludeSide] = useState<{ candidateId: string; chargeId: string } | null>(null)

  const canReview = hasPermission(auth.user?.permissions, 'DUPLICATE_REVIEW')

  const list = useQuery({
    queryKey: duplicateKeys.list({ page, size: PAGE_SIZE, status: 'OPEN' }),
    queryFn: () => duplicatesApi.listCandidates({ page, size: PAGE_SIZE, status: 'OPEN' }),
  })

  // Both sides of the resolved pair change review status and allocation
  // eligibility, so every read model touching either charge is invalidated.
  const refresh = (resolved: DuplicateCandidate) => {
    setProblem(null)
    void queryClient.invalidateQueries({ queryKey: duplicateKeys.lists() })
    void queryClient.invalidateQueries({ queryKey: duplicateKeys.detail(resolved.id) })
    void queryClient.invalidateQueries({ queryKey: costKeys.lists() })
    void queryClient.invalidateQueries({ queryKey: costKeys.detail(resolved.chargeFact.id) })
    void queryClient.invalidateQueries({ queryKey: costKeys.detail(resolved.matchedChargeFact.id) })
    void queryClient.invalidateQueries({ queryKey: allocationKeys.lists() })
    void queryClient.invalidateQueries({ queryKey: allocationKeys.byCharge(resolved.chargeFact.id) })
    void queryClient.invalidateQueries({ queryKey: allocationKeys.byCharge(resolved.matchedChargeFact.id) })
  }

  const keep = useMutation({
    mutationFn: (candidateId: string) => duplicatesApi.keep(candidateId, crypto.randomUUID()),
    onSuccess: refresh,
    onError: (error: unknown) => setProblem(toProblemDetail(error)),
    onSettled: () => setPendingCandidate(null),
  })

  const exclude = useMutation({
    mutationFn: (input: { candidateId: string; chargeId: string }) =>
      duplicatesApi.exclude(input.candidateId, input.chargeId, crypto.randomUUID()),
    onSuccess: refresh,
    onError: (error: unknown) => setProblem(toProblemDetail(error)),
    onSettled: () => setPendingExcludeSide(null),
  })

  const listProblem = list.error ? toProblemDetail(list.error) : null

  if (!canReview) {
    return <main className="settings-page"><h1>重复审核</h1><Alert type="warning" showIcon title="缺少 DUPLICATE_REVIEW 权限" /></main>
  }

  return (
    <main className="settings-page">
      <header className="page-header">
        <h1>重复审核</h1>
      </header>

      {problem && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          title={`${problem.title}（${problem.code}）`}
          description={problem.detail}
        />
      )}

      {listProblem && (
        <Alert
          type="error"
          showIcon
          title="无法加载疑似重复候选"
          description={(
            <>
              <div>{`${listProblem.title}（${listProblem.code}）`}</div>
              {listProblem.detail && <div>{listProblem.detail}</div>}
            </>
          )}
        />
      )}

      {!listProblem && (
      <Table<DuplicateCandidate>
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
        locale={{ emptyText: '没有待处理的疑似重复候选' }}
        columns={[
          { title: 'ID', dataIndex: 'id', width: 90 },
          {
            title: '证据摘要',
            render: (_, candidate) => (
              <Space orientation="vertical" size={2}>
                <Typography.Text>
                  #{candidate.chargeFact.id} {candidate.chargeFact.amount} {candidate.chargeFact.currency}
                  （{candidate.chargeFact.periodStart ?? '—'}）
                </Typography.Text>
                <Typography.Text type="secondary">
                  #{candidate.matchedChargeFact.id} {candidate.matchedChargeFact.amount} {candidate.matchedChargeFact.currency}
                  （{candidate.matchedChargeFact.periodStart ?? '—'}）
                </Typography.Text>
              </Space>
            ),
          },
          {
            title: '匹配类型',
            dataIndex: 'candidateType',
            width: 110,
            render: (type: string) => <Tag color={type === 'EXACT' ? 'red' : 'orange'}>{type}</Tag>,
          },
          { title: '匹配原因', dataIndex: 'matchReason', ellipsis: true },
          {
            title: '操作',
            width: 320,
            render: (_, candidate) => (
              <Space>
                <Button
                  size="small"
                  type="primary"
                  loading={pendingCandidate === candidate.id}
                  disabled={pendingExcludeSide !== null}
                  onClick={() => {
                    setPendingCandidate(candidate.id)
                    keep.mutate(candidate.id)
                  }}
                >
                  保留正常
                </Button>
                <Button
                  size="small"
                  danger
                  loading={pendingExcludeSide?.candidateId === candidate.id
                    && pendingExcludeSide.chargeId === candidate.chargeFact.id}
                  disabled={pendingCandidate !== null}
                  title={`排除 #${candidate.chargeFact.id}，保留 #${candidate.matchedChargeFact.id}`}
                  onClick={() => {
                    setPendingExcludeSide({ candidateId: candidate.id, chargeId: candidate.chargeFact.id })
                    exclude.mutate({ candidateId: candidate.id, chargeId: candidate.chargeFact.id })
                  }}
                >
                  排除源方 #{candidate.chargeFact.id}
                </Button>
                <Button
                  size="small"
                  danger
                  loading={pendingExcludeSide?.candidateId === candidate.id
                    && pendingExcludeSide.chargeId === candidate.matchedChargeFact.id}
                  disabled={pendingCandidate !== null}
                  title={`排除 #${candidate.matchedChargeFact.id}，保留 #${candidate.chargeFact.id}`}
                  onClick={() => {
                    setPendingExcludeSide({
                      candidateId: candidate.id,
                      chargeId: candidate.matchedChargeFact.id,
                    })
                    exclude.mutate({
                      candidateId: candidate.id,
                      chargeId: candidate.matchedChargeFact.id,
                    })
                  }}
                >
                  排除匹配方 #{candidate.matchedChargeFact.id}
                </Button>
              </Space>
            ),
          },
        ]}
      />
      )}
    </main>
  )
}
