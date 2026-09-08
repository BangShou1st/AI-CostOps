import { apiClient } from '../../auth/authApi'
import type {
  GatewayCredential, GatewayCredentialCreateInput, GatewayCredentialCreated,
  ModelCatalogRecord, PricingVersion, PricingVersionCreateInput, ProviderModelRecord, ServiceIdentity,
} from './gatewayTypes'

export const gatewayApi = {
  async listServiceIdentities(): Promise<ServiceIdentity[]> {
    return (await apiClient.get<ServiceIdentity[]>('/service-identities')).data
  },
  async createServiceIdentity(input: { code: string; name: string }): Promise<ServiceIdentity> {
    return (await apiClient.post<ServiceIdentity>('/service-identities', input)).data
  },
  async listGatewayCredentials(): Promise<GatewayCredential[]> {
    return (await apiClient.get<GatewayCredential[]>('/gateway-credentials')).data
  },
  async createGatewayCredential(input: GatewayCredentialCreateInput): Promise<GatewayCredentialCreated> {
    return (await apiClient.post<GatewayCredentialCreated>('/gateway-credentials', input)).data
  },
  async revokeGatewayCredential(id: string): Promise<GatewayCredential> {
    return (await apiClient.post<GatewayCredential>(`/gateway-credentials/${encodeURIComponent(id)}/revoke`)).data
  },
  async listModels(): Promise<ModelCatalogRecord[]> {
    return (await apiClient.get<ModelCatalogRecord[]>('/model-catalog')).data
  },
  async listProviderModels(): Promise<ProviderModelRecord[]> {
    return (await apiClient.get<ProviderModelRecord[]>('/provider-models')).data
  },
  async listPricingVersions(): Promise<PricingVersion[]> {
    return (await apiClient.get<PricingVersion[]>('/pricing-versions')).data
  },
  async createPricingVersion(input: PricingVersionCreateInput): Promise<PricingVersion> {
    return (await apiClient.post<PricingVersion>('/pricing-versions', input)).data
  },
  async activatePricingVersion(id: string): Promise<PricingVersion> {
    return (await apiClient.post<PricingVersion>(`/pricing-versions/${encodeURIComponent(id)}/activate`)).data
  },
}