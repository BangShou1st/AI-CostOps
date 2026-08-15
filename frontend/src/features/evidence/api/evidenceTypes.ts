/** Browser-facing Evidence review types; every identifier is a decimal string. */

export interface EvidenceSummary {
  id: string
  originalFilename: string
  mediaType: string | null
  sizeBytes: number
  sha256: string
  storageStatus: string
  storageErrorCode: string | null
  uploadedByMemberId: string
  createdAt: string
  updatedAt: string
}
