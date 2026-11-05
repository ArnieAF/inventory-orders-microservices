import type { ButtonHTMLAttributes, ReactNode, Ref } from 'react'
import { Link, type LinkProps } from 'react-router'
import { buttonBase, buttonClasses, buttonVariants, type ButtonSize, type ButtonVariant } from './buttonStyles'
import { cx } from './cx'
import { CircleNotchIcon } from './icons'

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  size?: ButtonSize
  loading?: boolean
  icon?: ReactNode
  ref?: Ref<HTMLButtonElement>
}

export function Button({
  variant = 'secondary',
  size = 'md',
  loading = false,
  icon,
  className,
  children,
  disabled,
  type = 'button',
  ...rest
}: ButtonProps) {
  return (
    <button
      type={type}
      className={buttonClasses(variant, size, className)}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      {...rest}
    >
      {loading ? <CircleNotchIcon aria-hidden className="size-4 shrink-0 animate-spin" /> : icon}
      {children}
    </button>
  )
}

export interface ButtonLinkProps extends LinkProps {
  variant?: ButtonVariant
  size?: ButtonSize
  icon?: ReactNode
}

export function ButtonLink({ variant = 'secondary', size = 'md', icon, className, children, ...rest }: ButtonLinkProps) {
  return (
    <Link className={buttonClasses(variant, size, typeof className === 'string' ? className : undefined)} {...rest}>
      {icon}
      {children}
    </Link>
  )
}

export interface IconButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'aria-label'> {
  label: string
  variant?: 'ghost' | 'secondary' | 'danger-ghost'
  children: ReactNode
  ref?: Ref<HTMLButtonElement>
}

/** Botón solo icono: siempre con nombre accesible (y tooltip nativo). */
export function IconButton({ label, variant = 'ghost', className, children, type = 'button', ...rest }: IconButtonProps) {
  return (
    <button
      type={type}
      aria-label={label}
      title={label}
      className={cx(
        buttonBase,
        buttonVariants[variant],
        'size-10 shrink-0 pointer-coarse:size-11 [&_svg]:size-5',
        className,
      )}
      {...rest}
    >
      {children}
    </button>
  )
}
