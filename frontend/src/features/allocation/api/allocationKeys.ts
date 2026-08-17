export const allocationKeys = {
  lists: () => ['allocation', 'list'] as const,
  byCharge: (chargeId: string) => ['allocation', 'charge', chargeId] as const,
  detail: (decisionId: string) => ['allocation', 'detail', decisionId] as const,
  targets: () => ['allocation', 'targets'] as const,
}
