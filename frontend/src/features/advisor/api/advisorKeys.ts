export const advisorKeys = {
  all: ['advisor'] as const,
  profile: () => [...advisorKeys.all, 'profile'] as const,
  job: (id: number | null) => [...advisorKeys.all, 'job', id] as const,
}
