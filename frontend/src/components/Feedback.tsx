import type { ReactNode } from 'react'
import { errorMessage } from '../api/errors'
import { Button } from './Button'
import { cx } from './cx'
import { ArrowClockwiseIcon, CheckCircleIcon, InfoIcon, WarningCircleIcon, WarningIcon, type Icon } from './icons'

export type BannerTone = 'error' | 'warning' | 'info' | 'success'

const bannerTone: Record<BannerTone, { box: string; icon: Icon }> = {
  error: { box: 'bg-danger-soft text-danger-text', icon: WarningCircleIcon },
  warning: { box: 'bg-warn-soft text-warn-text', icon: WarningIcon },
  info: { box: 'bg-info-soft text-info-text', icon: InfoIcon },
  success: { box: 'bg-ok-soft text-ok-text', icon: CheckCircleIcon },
}

export interface BannerProps {
  tone: BannerTone
  title?: ReactNode
  children?: ReactNode
  action?: ReactNode
  className?: string
  /** Anunciar al aparecer (errores tras una acción). */
  live?: boolean
}

export function Banner({ tone, title, children, action, className, live = false }: BannerProps) {
  const { box, icon: ToneIcon } = bannerTone[tone]
  return (
    <div
      role={live ? (tone === 'error' ? 'alert' : 'status') : undefined}
      className={cx('flex items-start gap-3 rounded-lg px-4 py-3 text-sm', box, className)}
    >
      <ToneIcon aria-hidden className="mt-0.5 size-5 shrink-0" weight="bold" />
      <div className="min-w-0 flex-1 [overflow-wrap:anywhere]">
        {title ? <p className="font-semibold">{title}</p> : null}
        {children ? <div className={cx(title ? 'mt-0.5' : undefined, 'leading-relaxed')}>{children}</div> : null}
      </div>
      {action ? <div className="shrink-0 self-center">{action}</div> : null}
    </div>
  )
}

export interface EmptyStateProps {
  icon: Icon
  title: string
  description?: ReactNode
  action?: ReactNode
  className?: string
}

export function EmptyState({ icon: StateIcon, title, description, action, className }: EmptyStateProps) {
  return (
    <div className={cx('flex flex-col items-center px-6 py-14 text-center', className)}>
      <span className="grid size-12 place-items-center rounded-xl bg-sunken text-ink-2">
        <StateIcon aria-hidden className="size-6" />
      </span>
      <h2 className="mt-4 text-base font-semibold text-ink">{title}</h2>
      {description ? <p className="mt-1.5 max-w-[46ch] text-sm leading-relaxed text-ink-2">{description}</p> : null}
      {action ? <div className="mt-5 flex flex-wrap justify-center gap-2">{action}</div> : null}
    </div>
  )
}

export interface ErrorStateProps {
  error: unknown
  title?: string
  onRetry?: () => void
  retrying?: boolean
  overrides?: Readonly<Record<string, string>>
  className?: string
}

export function ErrorState({ error, title = 'No se pudo cargar', onRetry, retrying, overrides, className }: ErrorStateProps) {
  return (
    <div role="alert" className={cx('flex flex-col items-center px-6 py-14 text-center', className)}>
      <span className="grid size-12 place-items-center rounded-xl bg-danger-soft text-danger-text">
        <WarningCircleIcon aria-hidden className="size-6" />
      </span>
      <h2 className="mt-4 text-base font-semibold text-ink">{title}</h2>
      <p className="mt-1.5 max-w-[46ch] text-sm leading-relaxed text-ink-2">{errorMessage(error, overrides)}</p>
      {onRetry ? (
        <Button
          className="mt-5"
          onClick={onRetry}
          loading={retrying ?? false}
          icon={<ArrowClockwiseIcon aria-hidden className="size-4" />}
        >
          Reintentar
        </Button>
      ) : null}
    </div>
  )
}

export function Skeleton({ className }: { className?: string }) {
  return <div aria-hidden className={cx('animate-pulse rounded-md bg-sunken motion-reduce:animate-none', className)} />
}

/** Contenedor accesible de un estado de carga. */
export function Loading({ label = 'Cargando…', children, className }: { label?: string; children: ReactNode; className?: string }) {
  return (
    <div role="status" aria-live="polite" className={className}>
      <span className="sr-only">{label}</span>
      {children}
    </div>
  )
}

export interface PageHeaderProps {
  title: string
  description?: ReactNode
  actions?: ReactNode
  back?: ReactNode
}

/** Cabecera de página. El h1 recibe el foco al navegar (ver AppShell). */
export function PageHeader({ title, description, actions, back }: PageHeaderProps) {
  return (
    <header className="mb-6 flex flex-col gap-4 sm:mb-8 sm:flex-row sm:items-end sm:justify-between">
      <div className="min-w-0">
        {back ? <div className="mb-3">{back}</div> : null}
        <h1 id="page-title" tabIndex={-1} data-focus-target className="title-label text-2xl text-ink sm:text-[1.75rem]">
          {title}
        </h1>
        {description ? <p className="mt-1.5 max-w-[65ch] text-sm leading-relaxed text-ink-2">{description}</p> : null}
      </div>
      {actions ? <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div> : null}
    </header>
  )
}

/** Panel con borde (sin sombra): agrupa contenido relacionado. */
export function Panel({
  children,
  className,
  as: Tag = 'section',
  labelledBy,
}: {
  children: ReactNode
  className?: string
  as?: 'section' | 'div' | 'article'
  labelledBy?: string
}) {
  return (
    <Tag aria-labelledby={labelledBy} className={cx('rounded-xl bg-surface ring-1 ring-line', className)}>
      {children}
    </Tag>
  )
}
