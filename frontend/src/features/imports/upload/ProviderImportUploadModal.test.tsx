import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { settingsApi } from '../../settings/api/settingsApi'
import { importsApi } from '../api/importsApi'
import { ProviderImportUploadModal, type ProviderImportUploadResult } from './ProviderImportUploadModal'

vi.mock('../../settings/api/settingsApi', () => ({
  settingsApi: { listProviderAccounts: vi.fn() },
}))
vi.mock('../api/importsApi', () => ({
  importsApi: { uploadProviderImport: vi.fn() },
}))

const mockedListAccounts = vi.mocked(settingsApi.listProviderAccounts)
const mockedUpload = vi.mocked(importsApi.uploadProviderImport)

const deepSeekAccount = {
  id: '11', providerCode: 'DEEPSEEK', displayName: 'DeepSeek 主账号', externalAccountRef: null,
  status: 'ACTIVE' as const, metadata: {}, createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z',
}
const openAiAccount = {
  id: '12', providerCode: 'OPENAI', displayName: 'OpenAI 主账号', externalAccountRef: null,
  status: 'ACTIVE' as const, metadata: {}, createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z',
}
const unknownAccount = {
  id: '13', providerCode: 'MYSTERY', displayName: '未知 Provider', externalAccountRef: null,
  status: 'ACTIVE' as const, metadata: {}, createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z',
}

function renderModal() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const onUploaded = vi.fn()
  render(
    <QueryClientProvider client={queryClient}>
      <ProviderImportUploadModal open onClose={vi.fn()} onUploaded={onUploaded} />
    </QueryClientProvider>,
  )
  return onUploaded
}

async function pickOption(comboboxLabel: RegExp, optionText: RegExp) {
  fireEvent.mouseDown(screen.getByLabelText(comboboxLabel))
  // antd renders option title + content nodes; click the last visible one.
  const matches = await screen.findAllByText(optionText)
  fireEvent.click(matches[matches.length - 1])
}

function attachFile(name = 'invoice.csv') {
  const input = document.querySelector('input[type="file"]') as HTMLInputElement
  const file = new File(['bytes'], name, { type: 'text/csv' })
  Object.defineProperty(input, 'files', { value: [file] })
  fireEvent.change(input)
  return file
}

beforeEach(() => {
  vi.clearAllMocks()
  mockedListAccounts.mockResolvedValue({
    items: [deepSeekAccount, openAiAccount, unknownAccount],
    page: 0, size: 200, totalElements: 3, totalPages: 1,
  })
})

describe('ProviderImportUploadModal', () => {
  it('offers only FILE_EXPORT for DeepSeek', async () => {
    renderModal()
    await pickOption(/Provider 账号/, /DeepSeek 主账号/)

    fireEvent.mouseDown(screen.getByLabelText(/来源类型/))
    expect(await screen.findByText('文件导出（FILE_EXPORT）')).toBeInTheDocument()
    expect(screen.queryByText('用量 API JSON（USAGE_API_JSON）')).not.toBeInTheDocument()
    expect(screen.queryByText('成本 API JSON（COSTS_API_JSON）')).not.toBeInTheDocument()
  })

  it('offers all three source types for OpenAI', async () => {
    renderModal()
    await pickOption(/Provider 账号/, /OpenAI 主账号/)

    fireEvent.mouseDown(screen.getByLabelText(/来源类型/))
    expect(await screen.findByText('文件导出（FILE_EXPORT）')).toBeInTheDocument()
    expect(await screen.findByText('用量 API JSON（USAGE_API_JSON）')).toBeInTheDocument()
    expect(await screen.findByText('成本 API JSON（COSTS_API_JSON）')).toBeInTheDocument()
  })

  it('marks unknown provider as unsupported without guessing a source type', async () => {
    renderModal()
    await pickOption(/Provider 账号/, /未知 Provider/)

    expect(await screen.findByText(/该 Provider 暂不支持上传/)).toBeInTheDocument()
    expect(screen.getByLabelText(/来源类型/)).toBeDisabled()
    expect(screen.getByRole('button', { name: /上\s*传/ })).toBeDisabled()
  })

  it('requires provider account, source type, and file', async () => {
    renderModal()
    expect(screen.getByRole('button', { name: /上\s*传/ })).toBeDisabled()
  })

  it('submits multipart fields exactly and surfaces duplicate flags', async () => {
    const onUploaded = renderModal()
    mockedUpload.mockResolvedValue({
      evidenceId: '1', importBatchId: '2', latestAttemptId: '3',
      batchStatus: 'PENDING', duplicateEvidence: true, duplicateBatch: true,
    })

    await pickOption(/Provider 账号/, /DeepSeek 主账号/)
    await pickOption(/来源类型/, /FILE_EXPORT/)
    const file = attachFile()

    fireEvent.click(screen.getByRole('button', { name: /上\s*传/ }))
    await waitFor(() => {
      expect(mockedUpload).toHaveBeenCalledWith({ providerAccountId: '11', sourceType: 'FILE_EXPORT', file })
    })
    await waitFor(() => {
      expect(onUploaded).toHaveBeenCalledWith(expect.objectContaining({
        importBatchId: '2', duplicateEvidence: true, duplicateBatch: true,
      }) satisfies ProviderImportUploadResult)
    })
  })

  it('surfaces explicit mutation errors without swallowing them', async () => {
    renderModal()
    mockedUpload.mockRejectedValue({
      isAxiosError: true,
      response: { data: {
        title: 'Conflict', status: 409, detail: 'Import cannot be retried.',
        code: 'STATE_CONFLICT', traceId: 't9',
      } },
    })

    await pickOption(/Provider 账号/, /DeepSeek 主账号/)
    await pickOption(/来源类型/, /FILE_EXPORT/)
    attachFile()
    fireEvent.click(screen.getByRole('button', { name: /上\s*传/ }))

    expect(await screen.findByText('状态冲突')).toBeInTheDocument()
    expect(screen.getByText('当前资源状态已变化，请刷新后重试。')).toBeInTheDocument()
  })
})
