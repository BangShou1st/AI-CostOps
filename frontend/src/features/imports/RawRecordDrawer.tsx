import { Descriptions, Drawer } from 'antd'
import { useQuery } from '@tanstack/react-query'
import { importKeys } from './api/importKeys'
import { importsApi } from './api/importsApi'

/**
 * Lazy Raw Record payload detail. The persisted sanitized payload is rendered
 * as escaped JSON text — never as injected HTML.
 */
export function RawRecordDrawer({
  importId,
  attemptId,
  recordId,
  onClose,
}: {
  importId: string
  attemptId: string
  recordId: string | null
  onClose: () => void
}) {
  const detail = useQuery({
    queryKey: importKeys.rawRecord(importId, attemptId, recordId ?? ''),
    queryFn: () => importsApi.getRawRecord(importId, attemptId, recordId!),
    enabled: recordId !== null,
  })

  const record = detail.data
  return (
    <Drawer
      open={recordId !== null}
      onClose={onClose}
      width={560}
      title={record ? `原始记录 #${record.recordIndex}` : '原始记录'}
    >
      {detail.isLoading && <div role="status">正在加载原始记录…</div>}
      {record && (
        <>
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="记录位置">{record.recordLocator}</Descriptions.Item>
            <Descriptions.Item label="Provider 键">{record.providerRecordKey ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="归一化状态">{record.normalizeStatus ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="用量开始">{record.usageStart ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="用量结束">{record.usageEnd ?? '—'}</Descriptions.Item>
          </Descriptions>
          <h3>原始 payload（已脱敏）</h3>
          <pre className="raw-payload">{JSON.stringify(record.rawPayload, null, 2)}</pre>
          {record.normalizedPayload !== null && (
            <>
              <h3>归一化 payload（已脱敏）</h3>
              <pre className="raw-payload">{JSON.stringify(record.normalizedPayload, null, 2)}</pre>
            </>
          )}
        </>
      )}
    </Drawer>
  )
}
