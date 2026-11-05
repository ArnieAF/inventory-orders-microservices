import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { notificationCenter, useNotificationCenterState } from '../realtime/center'
import type { ToastItem, ToastTone } from '../realtime/notificationCenter'
import { cx } from './cx'
import {
  ArrowsClockwiseIcon,
  CheckCircleIcon,
  InfoIcon,
  ReceiptIcon,
  WarningCircleIcon,
  WarningIcon,
  XIcon,
  type Icon,
} from './icons'

const TONE: Record<ToastTone, { icon: Icon; accent: string }> = {
  info: { icon: InfoIcon, accent: 'text-info-text bg-info-soft' },
  success: { icon: CheckCircleIcon, accent: 'text-ok-text bg-ok-soft' },
  warning: { icon: WarningIcon, accent: 'text-warn-text bg-warn-soft' },
  error: { icon: WarningCircleIcon, accent: 'text-danger-text bg-danger-soft' },
}

const BY_TYPE: Record<NonNullable<ToastItem['notificationType']>, Icon> = {
  NEW_ORDER: ReceiptIcon,
  LOW_STOCK: WarningIcon,
  ORDER_STATUS_CHANGED: ArrowsClockwiseIcon,
}

const DURATION_MS: Record<ToastTone, number> = { info: 7000, success: 5000, warning: 9000, error: 9000 }

function ToastView({ toast }: { toast: ToastItem }) {
  const [paused, setPaused] = useState(false)
  const { accent } = TONE[toast.tone]
  const ToneIcon = toast.notificationType ? BY_TYPE[toast.notificationType] : TONE[toast.tone].icon

  useEffect(() => {
    if (paused) return
    const timer = setTimeout(() => notificationCenter.dismissToast(toast.id), DURATION_MS[toast.tone])
    return () => clearTimeout(timer)
  }, [paused, toast.id, toast.tone])

  return (
    <li
      className="pointer-events-auto flex w-full items-start gap-3 rounded-xl bg-raised p-3.5 shadow-pop ring-1 ring-line motion-safe:animate-toast-in"
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
      onFocus={() => setPaused(true)}
      onBlur={() => setPaused(false)}
    >
      <span className={cx('grid size-8 shrink-0 place-items-center rounded-lg', accent)}>
        <ToneIcon aria-hidden className="size-4.5" weight="bold" />
      </span>
      <div className="min-w-0 flex-1 pt-0.5">
        {/* Texto plano: el contenido de las notificaciones nunca se interpreta como HTML. */}
        <p className="line-clamp-2 text-sm font-semibold text-ink [overflow-wrap:anywhere]">{toast.title}</p>
        {toast.message ? (
          <p className="mt-0.5 line-clamp-3 text-sm leading-snug text-ink-2 [overflow-wrap:anywhere]">{toast.message}</p>
        ) : null}
        {toast.href ? (
          <Link
            to={toast.href}
            onClick={() => notificationCenter.dismissToast(toast.id)}
            className="mt-1.5 inline-flex min-h-8 items-center text-sm font-semibold text-accent-text underline-offset-4 hover:underline pointer-coarse:min-h-11"
          >
            {toast.hrefLabel ?? 'Ver'}
          </Link>
        ) : null}
      </div>
      <button
        type="button"
        onClick={() => notificationCenter.dismissToast(toast.id)}
        aria-label="Cerrar aviso"
        title="Cerrar aviso"
        className="-mt-1 -mr-1 grid size-9 shrink-0 place-items-center rounded-lg text-ink-3 transition-colors hover:bg-sunken hover:text-ink pointer-coarse:size-11"
      >
        <XIcon aria-hidden className="size-4" />
      </button>
    </li>
  )
}

/** Región de avisos en vivo (siempre montada para que los lectores de pantalla la anuncien). */
export function Toaster() {
  const { toasts } = useNotificationCenterState()
  return (
    <section
      aria-label="Avisos"
      className="pointer-events-none fixed inset-x-3 bottom-[calc(4.75rem+env(safe-area-inset-bottom))] z-40 md:inset-x-auto md:right-6 md:bottom-6 md:w-96"
    >
      <ol aria-live="polite" aria-relevant="additions text" className="flex flex-col gap-2">
        {toasts.map((toast) => (
          <ToastView key={toast.id} toast={toast} />
        ))}
      </ol>
    </section>
  )
}
