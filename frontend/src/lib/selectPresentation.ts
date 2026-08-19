export const READABLE_SELECT_PROPS = {
  popupMatchSelectWidth: 360,
  classNames: { popup: { root: 'readable-select-popup' } },
} as const

/** Keeps the full meaning available for both the option and the selected value. */
export function readableOption<T extends string>(value: T, label: string) {
  return { value, label, title: label }
}
