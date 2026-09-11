import { apiClient } from '../../auth/authApi'
import type {
  CreateConnectionInput,
  CredentialRow,
  DiscoveryRow,
  ModelProbeCapabilities,
  Page,
  ProbeResult,
  PromotionResult,
  ProviderConnection,
  ProviderTemplate,
} from './providerHubTypes'

/** Real M18 Provider Hub contract client. Credential payloads send secrets; responses carry labels only. */
export const providerHubApi = {
  async templates(): Promise<ProviderTemplate[]> {
    return (await apiClient.get<ProviderTemplate[]>('/provider-templates')).data
  },
  async connections(page: number, size: number): Promise<Page<ProviderConnection>> {
    return (await apiClient.get<Page<ProviderConnection>>('/provider-connections', { params: { page, size } })).data
  },
  async connection(id: number): Promise<ProviderConnection> {
    return (await apiClient.get<ProviderConnection>(`/provider-connections/${id}`)).data
  },
  async createConnection(input: CreateConnectionInput): Promise<ProviderConnection> {
    return (await apiClient.post<ProviderConnection>('/provider-connections', input)).data
  },
  async updateDraft(id: number, input: CreateConnectionInput): Promise<ProviderConnection> {
    return (await apiClient.put<ProviderConnection>(`/provider-connections/${id}`, input)).data
  },
  async revisions(id: number): Promise<ProviderConnection[]> {
    return (await apiClient.get<ProviderConnection[]>(`/provider-connections/${id}/revisions`)).data
  },
  async createRevision(id: number): Promise<ProviderConnection> {
    return (await apiClient.post<ProviderConnection>(`/provider-connections/${id}/revisions`)).data
  },
  async activate(id: number): Promise<ProviderConnection> {
    return (await apiClient.post<ProviderConnection>(`/provider-connections/${id}/activate`)).data
  },
  async probe(id: number): Promise<ProbeResult> {
    return (await apiClient.post<ProbeResult>(`/provider-connections/${id}/probe`)).data
  },
  async models(id: number): Promise<DiscoveryRow[]> {
    return (await apiClient.get<DiscoveryRow[]>(`/provider-connections/${id}/models`)).data
  },
  async refreshModels(id: number, fetchLive: boolean, modelNames: string[] = []): Promise<DiscoveryRow[]> {
    return (await apiClient.post<DiscoveryRow[]>(`/provider-connections/${id}/models/refresh`, { fetchLive, modelNames })).data
  },
  async manualModel(id: number, modelName: string): Promise<DiscoveryRow> {
    return (await apiClient.post<DiscoveryRow>(`/provider-connections/${id}/models/manual`, { modelName })).data
  },
  async probeModel(id: number, discoveryId: number): Promise<ModelProbeCapabilities> {
    return (await apiClient.post<ModelProbeCapabilities>(`/provider-connections/${id}/models/${discoveryId}/probe`)).data
  },
  async promoteModel(id: number, discoveryId: number): Promise<PromotionResult> {
    return (await apiClient.post<PromotionResult>(`/provider-connections/${id}/models/${discoveryId}/promote`)).data
  },
  async credentials(id: number): Promise<CredentialRow[]> {
    return (await apiClient.get<CredentialRow[]>(`/provider-connections/${id}/credentials`)).data
  },
  async createCredential(id: number, rawSecret: string, safeLabel: string): Promise<CredentialRow> {
    return (await apiClient.post<CredentialRow>(`/provider-connections/${id}/credentials`, { rawSecret, safeLabel })).data
  },
  async rotateCredential(id: number, rawSecret: string, safeLabel: string): Promise<CredentialRow> {
    return (await apiClient.post<CredentialRow>(`/provider-connections/${id}/credentials/rotate`, { rawSecret, safeLabel })).data
  },
  async revokeCredential(id: number, credentialId: number): Promise<void> {
    await apiClient.post(`/provider-connections/${id}/credentials/${credentialId}/revoke`)
  },
}
