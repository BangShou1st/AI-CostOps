import { Input, Select, Table, Tag } from 'antd'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { importKeys } from './api/importKeys'
import { importsApi } from './api/importsApi'
import type { AttemptSummary, IssueSeverity, IssueSummary, RawRecordNormalizeStatus, RawRecordSummary } from './api/importTypes'
import { RawRecordDrawer } from './RawRecordDrawer'

const PAGE_SIZE = 50

/**
 * Historical Attempt selection plus bounded Issue / Raw Record review. The
 * latest Attempt is selected by default; selecting an older Attempt switches
 * Issues and Raw Records to that immutable lineage, so failed/canceled history
 * stays visible after a retry.
 */
export function AttemptReview({ importId }: { importId: string }) {
  const [attemptPage, setAttemptPage] = useState(0)
  const [selectedAttemptId, setSelectedAttemptId] = useState<string | null>(null)
  const [wasFollowingLatest, setWasFollowingLatest] = useState(true)
  const [issuePage, setIssuePage] = useState(0)
  const [severity, setSeverity] = useState<IssueSeverity | undefined>()
  const [issueCode, setIssueCode] = useState<string | undefined>()
  const [rawPage, setRawPage] = useState(0)
  const [normalizeStatus, setNormalizeStatus] = useState<RawRecordNormalizeStatus | undefined>()
  const [drawerRecordId, setDrawerRecordId] = useState<string | null>(null)

  const attempts = useQuery({
    queryKey: importKeys.attempts(importId, attemptPage, PAGE_SIZE),
    queryFn: () => importsApi.listAttempts(importId, attemptPage, PAGE_SIZE),
  })

  const attemptsList = attempts.data?.items ?? []
  const latestAttemptId = attemptsList.length > 0 ? attemptsList[0].id : null

  // Follow the latest Attempt by default; after a retry adds a successor, move
  // to it only when the reviewer had been following the previous latest. The
  // follow-up is limited to the first page so paging through history never
  // silently reselects the current page's first row.
  useEffect(() => {
    if (attemptPage !== 0 || latestAttemptId === null) return
    if (selectedAttemptId === null || (wasFollowingLatest && selectedAttemptId !== latestAttemptId)) {
      setSelectedAttemptId(latestAttemptId)
      setWasFollowingLatest(true)
    }
  }, [attemptPage, latestAttemptId, selectedAttemptId, wasFollowingLatest])

  const selectAttempt = (attemptId: string) => {
    setSelectedAttemptId(attemptId)
    setWasFollowingLatest(attemptId === latestAttemptId)
    setIssuePage(0)
    setRawPage(0)
  }

  const issues = useQuery({
    queryKey: importKeys.issues(importId, selectedAttemptId ?? '', {
      page: issuePage, size: PAGE_SIZE, severity, issueCode,
    }),
    queryFn: () => importsApi.listIssues(importId, selectedAttemptId!, {
      page: issuePage, size: PAGE_SIZE, severity, issueCode,
    }),
    enabled: selectedAttemptId !== null,
  })

  const rawRecords = useQuery({
    queryKey: importKeys.rawRecords(importId, selectedAttemptId ?? '', {
      page: rawPage, size: PAGE_SIZE, normalizeStatus,
    }),
    queryFn: () => importsApi.listRawRecords(importId, selectedAttemptId!, {
      page: rawPage, size: PAGE_SIZE, normalizeStatus,
    }),
    enabled: selectedAttemptId !== null,
  })

  return (
    <div className="attempt-review">
      <section className="attempt-review-section">
        <h2>尝试历史</h2>
        <Table<AttemptSummary>
          rowKey="id"
          size="small"
          loading={attempts.isLoading}
          dataSource={attemptsList}
          pagination={{
            current: attemptPage + 1,
            pageSize: PAGE_SIZE,
            total: attempts.data?.totalElements ?? 0,
            showSizeChanger: false,
            onChange: (nextPage) => setAttemptPage(nextPage - 1),
          }}
          rowClassName={(row) => (row.id === selectedAttemptId ? 'attempt-row-selected' : '')}
          onRow={(row) => ({ onClick: () => selectAttempt(row.id) })}
          columns={[
            { title: '尝试 #', dataIndex: 'attemptNo' },
            { title: '状态', dataIndex: 'status' },
            { title: '触发', dataIndex: 'triggerType' },
            { title: '错误', dataIndex: 'errorCode' },
            { title: '完成时间', dataIndex: 'finishedAt' },
          ]}
        />
      </section>

      <section className="attempt-review-section">
        <header className="section-header">
          <h2>问题</h2>
          <Select
            allowClear
            placeholder="严重级别"
            aria-label="严重级别"
            value={severity}
            onChange={(value: IssueSeverity | undefined) => {
              setSeverity(value)
              setIssuePage(0)
            }}
            options={[{ value: 'WARN', label: 'WARN' }, { value: 'ERROR', label: 'ERROR' }]}
            style={{ width: 140 }}
          />
          <Input
            allowClear
            placeholder="问题代码（回车过滤）"
            aria-label="问题代码"
            defaultValue=""
            onPressEnter={(event) => {
              const value = event.currentTarget.value.trim()
              setIssueCode(value || undefined)
              setIssuePage(0)
            }}
            onClear={() => {
              setIssueCode(undefined)
              setIssuePage(0)
            }}
            style={{ width: 220 }}
          />
        </header>
        <Table<IssueSummary>
          rowKey="id"
          size="small"
          loading={issues.isLoading}
          dataSource={issues.data?.items ?? []}
          pagination={{
            current: issuePage + 1,
            pageSize: PAGE_SIZE,
            total: issues.data?.totalElements ?? 0,
            showSizeChanger: false,
            onChange: (nextPage) => setIssuePage(nextPage - 1),
          }}
          columns={[
            { title: '严重级别', dataIndex: 'severity', render: (value) => <Tag>{value}</Tag> },
            { title: '代码', dataIndex: 'issueCode' },
            { title: '记录位置', dataIndex: 'recordLocator' },
            { title: '字段', dataIndex: 'fieldName' },
            { title: '消息', dataIndex: 'message' },
            { title: '脱敏值', dataIndex: 'rawValueMasked' },
          ]}
        />
      </section>

      <section className="attempt-review-section">
        <header className="section-header">
          <h2>原始记录</h2>
          <Select
            allowClear
            placeholder="归一化状态"
            aria-label="归一化状态"
            value={normalizeStatus}
            onChange={(value: RawRecordNormalizeStatus | undefined) => {
              setNormalizeStatus(value)
              setRawPage(0)
            }}
            options={[
              { value: 'NORMALIZED', label: 'NORMALIZED' },
              { value: 'WARN', label: 'WARN' },
              { value: 'ERROR', label: 'ERROR' },
            ]}
            style={{ width: 160 }}
          />
        </header>
        <Table<RawRecordSummary>
          rowKey="id"
          size="small"
          loading={rawRecords.isLoading}
          dataSource={rawRecords.data?.items ?? []}
          pagination={{
            current: rawPage + 1,
            pageSize: PAGE_SIZE,
            total: rawRecords.data?.totalElements ?? 0,
            showSizeChanger: false,
            onChange: (nextPage) => setRawPage(nextPage - 1),
          }}
          onRow={(row) => ({ onClick: () => setDrawerRecordId(row.id) })}
          columns={[
            { title: '索引', dataIndex: 'recordIndex' },
            { title: '记录位置', dataIndex: 'recordLocator' },
            { title: '归一化状态', dataIndex: 'normalizeStatus' },
            { title: '键数', render: (_, row) => row.rawPayloadKeys.keyCount },
            { title: '键', render: (_, row) => row.rawPayloadKeys.keys.join(', ') },
            { title: '截断', render: (_, row) => (row.rawPayloadKeys.keysTruncated ? '是' : '否') },
          ]}
        />
      </section>

      <RawRecordDrawer
        importId={importId}
        attemptId={selectedAttemptId ?? ''}
        recordId={drawerRecordId}
        onClose={() => setDrawerRecordId(null)}
      />
    </div>
  )
}
