import { cx } from './cx'

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'danger-ghost'
export type ButtonSize = 'md' | 'sm'

export const buttonBase =
  'relative inline-flex items-center justify-center gap-2 rounded-lg font-medium whitespace-nowrap select-none ' +
  'transition-[background-color,color,box-shadow,transform] duration-150 ease-out ' +
  'motion-safe:active:scale-[0.98] disabled:pointer-events-none disabled:opacity-50'

export const buttonVariants: Record<ButtonVariant, string> = {
  primary: 'bg-accent text-accent-ink hover:bg-accent-hover',
  secondary: 'bg-surface text-ink ring-1 ring-line-strong ring-inset hover:bg-sunken',
  ghost: 'text-ink-2 hover:bg-sunken hover:text-ink',
  danger: 'bg-danger text-danger-ink hover:bg-danger-hover',
  'danger-ghost': 'text-danger-text hover:bg-danger-soft',
}

const sizes: Record<ButtonSize, string> = {
  md: 'h-10 px-4 text-sm pointer-coarse:h-11',
  sm: 'h-8 px-3 text-sm pointer-coarse:h-11',
}

export function buttonClasses(variant: ButtonVariant = 'secondary', size: ButtonSize = 'md', className?: string) {
  return cx(buttonBase, buttonVariants[variant], sizes[size], className)
}
