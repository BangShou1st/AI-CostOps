export function hasPermission(permissions: readonly string[] | undefined, code: string): boolean {
  return permissions?.includes(code) ?? false
}
