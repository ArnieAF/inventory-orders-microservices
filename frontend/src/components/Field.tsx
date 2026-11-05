import { useId, type InputHTMLAttributes, type ReactNode, type Ref, type SelectHTMLAttributes, type TextareaHTMLAttributes } from 'react'
import { cx } from './cx'
import { CaretDownIcon, WarningCircleIcon } from './icons'

export const controlClasses =
  'block w-full min-w-0 rounded-lg bg-surface text-ink ring-1 ring-line-strong ring-inset ' +
  'text-base sm:text-sm placeholder:text-ink-3 transition-[box-shadow] duration-150 ' +
  'focus:outline-none focus:ring-2 focus:ring-focus ' +
  'aria-invalid:ring-2 aria-invalid:ring-danger ' +
  'disabled:cursor-not-allowed disabled:bg-sunken disabled:text-ink-3'

interface FieldShellProps {
  id: string
  label: ReactNode
  hint?: ReactNode
  error?: string | undefined
  /** Texto a la derecha de la etiqueta (p. ej. "Opcional" o un contador). */
  aside?: ReactNode
  className?: string
  children: ReactNode
}

function FieldShell({ id, label, hint, error, aside, className, children }: FieldShellProps) {
  return (
    <div className={cx('flex min-w-0 flex-col gap-1.5', className)}>
      <div className="flex items-baseline justify-between gap-3">
        <label htmlFor={id} className="text-sm font-medium text-ink">
          {label}
        </label>
        {aside ? <span className="shrink-0 text-xs text-ink-3 tabular-nums">{aside}</span> : null}
      </div>
      {children}
      {hint && !error ? (
        <p id={`${id}-hint`} className="text-xs text-ink-3">
          {hint}
        </p>
      ) : null}
      {error ? (
        <p id={`${id}-error`} className="flex items-start gap-1.5 text-xs font-medium text-danger-text">
          <WarningCircleIcon aria-hidden className="mt-px size-3.5 shrink-0" weight="bold" />
          <span>{error}</span>
        </p>
      ) : null}
    </div>
  )
}

function describedBy(id: string, hint: unknown, error: unknown): string | undefined {
  if (error) return `${id}-error`
  if (hint) return `${id}-hint`
  return undefined
}

export interface TextFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'id'> {
  label: ReactNode
  hint?: ReactNode
  error?: string | undefined
  aside?: ReactNode
  /** Elemento a la derecha dentro del campo (p. ej. mostrar contraseña). */
  trailing?: ReactNode
  /** Elemento a la izquierda dentro del campo (icono). */
  leading?: ReactNode
  containerClassName?: string
  ref?: Ref<HTMLInputElement>
}

export function TextField({
  label,
  hint,
  error,
  aside,
  trailing,
  leading,
  containerClassName,
  className,
  ...rest
}: TextFieldProps) {
  const id = useId()
  return (
    <FieldShell id={id} label={label} hint={hint} error={error} aside={aside} className={containerClassName}>
      <div className="relative">
        {leading ? (
          <span className="pointer-events-none absolute inset-y-0 left-3 flex items-center text-ink-3 [&_svg]:size-4">
            {leading}
          </span>
        ) : null}
        <input
          id={id}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy(id, hint, error)}
          className={cx(controlClasses, 'h-10 px-3 pointer-coarse:h-11', leading ? 'pl-9' : null, trailing ? 'pr-12' : null, className)}
          {...rest}
        />
        {trailing ? <span className="absolute inset-y-0 right-0 flex items-center pr-0.5">{trailing}</span> : null}
      </div>
    </FieldShell>
  )
}

export interface TextAreaFieldProps extends Omit<TextareaHTMLAttributes<HTMLTextAreaElement>, 'id'> {
  label: ReactNode
  hint?: ReactNode
  error?: string | undefined
  aside?: ReactNode
  containerClassName?: string
}

export function TextAreaField({ label, hint, error, aside, containerClassName, className, ...rest }: TextAreaFieldProps) {
  const id = useId()
  return (
    <FieldShell id={id} label={label} hint={hint} error={error} aside={aside} className={containerClassName}>
      <textarea
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(id, hint, error)}
        className={cx(controlClasses, 'min-h-24 resize-y px-3 py-2.5 leading-relaxed', className)}
        {...rest}
      />
    </FieldShell>
  )
}

export interface SelectFieldProps extends Omit<SelectHTMLAttributes<HTMLSelectElement>, 'id'> {
  label: ReactNode
  hint?: ReactNode
  error?: string | undefined
  containerClassName?: string
  /** Etiqueta solo para lectores de pantalla (barras de herramientas compactas). */
  hideLabel?: boolean
}

export function SelectField({
  label,
  hint,
  error,
  containerClassName,
  className,
  hideLabel,
  children,
  ...rest
}: SelectFieldProps) {
  const id = useId()
  const select = (
    <div className="relative">
      <select
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(id, hint, error)}
        className={cx(controlClasses, 'h-10 appearance-none pr-9 pl-3 pointer-coarse:h-11', className)}
        {...rest}
      >
        {children}
      </select>
      <CaretDownIcon aria-hidden className="pointer-events-none absolute top-1/2 right-3 size-4 -translate-y-1/2 text-ink-3" />
    </div>
  )
  if (hideLabel) {
    return (
      <div className={containerClassName}>
        <label htmlFor={id} className="sr-only">
          {label}
        </label>
        {select}
      </div>
    )
  }
  return (
    <FieldShell id={id} label={label} hint={hint} error={error} className={containerClassName}>
      {select}
    </FieldShell>
  )
}
