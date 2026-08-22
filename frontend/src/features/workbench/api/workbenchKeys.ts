export const workbenchKeys = {
  all: ['workbench'] as const,
  view: (billingPeriodId?: string) =>
    billingPeriodId
      ? (['workbench', { billingPeriodId }] as const)
      : (['workbench', 'current'] as const),
}
