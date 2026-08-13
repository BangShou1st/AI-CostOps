import { useMutation, type UseMutationOptions } from '@tanstack/react-query'
import axios from 'axios'
import { useAuth } from '../auth/AuthSessionProvider'

/**
 * Mutation wrapper for authorization-sensitive settings actions.
 *
 * On a 403 FORBIDDEN it refetches /auth/me exactly once so navigation and
 * action visibility catch up with backend truth, then surfaces the original
 * forbidden error. The mutation itself is never retried (retry: false, so the
 * error handler runs at most once per mutation) and a non-forbidden failure
 * never triggers a refresh.
 */
export function useAuthorizationMutation<TData, TVariables = void>(
  options: UseMutationOptions<TData, unknown, TVariables>,
) {
  const auth = useAuth()

  return useMutation<TData, unknown, TVariables>({
    ...options,
    retry: false,
    onError: (error, variables, onMutateResult, context) => {
      if (isForbiddenError(error)) {
        auth.refreshMe().catch(() => { /* surface the original forbidden error below */ })
      }
      options.onError?.(error, variables, onMutateResult, context)
    },
  })
}

function isForbiddenError(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 403
}
