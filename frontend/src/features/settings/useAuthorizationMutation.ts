import { useMutation, type UseMutationOptions } from '@tanstack/react-query'
import axios from 'axios'
import { useAuth } from '../auth/AuthSessionProvider'

/**
 * Mutation wrapper for authorization-sensitive settings actions.
 *
 * On a 403 FORBIDDEN it awaits a single /auth/me refetch so navigation and
 * action visibility catch up with backend truth before the original forbidden
 * error is surfaced. The mutation itself is never retried (retry: false, so
 * the error handler runs at most once per mutation) and a non-forbidden
 * failure never triggers a refresh. A failed refreshMe never replaces the
 * original forbidden error.
 */
export function useAuthorizationMutation<TData, TVariables = void>(
  options: UseMutationOptions<TData, unknown, TVariables>,
) {
  const auth = useAuth()

  return useMutation<TData, unknown, TVariables>({
    ...options,
    retry: false,
    onError: async (error, variables, onMutateResult, context) => {
      if (isForbiddenError(error)) {
        try {
          await auth.refreshMe()
        } catch {
          // Surface the original forbidden error below, never the refresh failure.
        }
      }
      options.onError?.(error, variables, onMutateResult, context)
    },
  })
}

function isForbiddenError(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 403
}
